package microseriveces.streams.splunk.demo;

import com.sun.deploy.services.Service;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Predicate;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.StreamsConfig;

import org.eclipse.jetty.server.Server;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ManagedAsync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import microservices.streams.splunk.demo.Order;
import microservices.streams.splunk.demo.OrderState;

import io.confluent.examples.streams.interactivequeries.HostStoreInfo;
import io.confluent.examples.streams.interactivequeries.MetadataService;
import  microseriveces.streams.splunk.demo.domain.Schemas;
import microseriveces.streams.splunk.demo.domain.beans.OrderBean;
import io.confluent.examples.streams.microservices.util.Paths;

import static microseriveces.streams.splunk.demo.domain.Schemas.Topics.ORDERS;
import static microseriveces.streams.splunk.demo.domain.beans.OrderBean.fromBean;
import static microseriveces.streams.splunk.demo.domain.beans.OrderBean.toBean;
import static microseriveces.streams.splunk.demo.util.MicroserviceUtils.addShutdownHookAndBlock;
import static microseriveces.streams.splunk.demo.util.MicroserviceUtils.baseStreamsConfig;
import static microseriveces.streams.splunk.demo.util.MicroserviceUtils.setTimeout;
import static microseriveces.streams.splunk.demo.util.MicroserviceUtils.startJetty;
import static microseriveces.streams.splunk.demo.util.MicroserviceUtils.startProducer;

import static org.apache.kafka.streams.state.StreamsMetadata.NOT_AVAILABLE;

public class OrdersService implements Service {

    private static final Logger log = LoggerFactory.getLogger(OrdersService.class);
    private static final String CALL_TIMEOUT = "10000";
    private static final String ORDERS_STORE_NAME = "orders-store";
    private final String SERVICE_APP_ID = getClass().getSimpleName();
    private final Client client = ClientBuilder.newBuilder().register(JacksonFeature.class).build();
    private Server jettyServer;
    private String host;
    private int port;
    private KafkaStreams streams = null;
    private MetadataService metadataService;
    private KafkaProducer<String, Order> producer;

    //In a real implementation we would need to (a) support outstanding requests for the same Id/filter from
    // different users and (b) periodically purge old entries from this map.
    private Map<String, FilteredResponse<String, Order>> outstandingRequests = new ConcurrentHashMap<>();

    public OrdersService(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public OrdersService(String host) {
        this(host, 0);
    }

    /**
     * Create a table of orders which we can query. When the table is updated
     * we check to see if there is an outstanding HTTP GET request waiting to be
     * fulfilled.
     */
    private StreamsBuilder createOrdersMaterializedView() {
        StreamsBuilder builder = new StreamsBuilder();
        builder.table(ORDERS.name(), Consumed.with(ORDERS.keySerde(), ORDERS.valueSerde()), Materialized.as(ORDERS_STORE_NAME))
                .toStream().foreach(this::maybeCompleteLongPollGet);
        return builder;
    }

    private void maybeCompleteLongPollGet(String id, Order order) {
        FilteredResponse<String, Order> callback = outstandingRequests.get(id);
        if (callback != null && callback.predicate.test(id, order)) {
            callback.asyncResponse.resume(toBean(order));
        }
    }

    /**
     * Perform a "Long-Poll" styled get. This method will attempt to get the value for the passed key
     * blocking until the key is available or passed timeout is reached. Non-blocking IO is used to
     * implement this, but the API will block the calling thread if no metastore data is available
     * (for example on startup or during a rebalance)
     *
     * @param id - the key of the value to retrieve
     * @param timeout - the timeout for the long-poll
     * @param asyncResponse - async response used to trigger the poll early should the appropriate
     * value become available
     */
    @GET
    @ManagedAsync
    @Path("/orders/{id}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    public void getWithTimeout(@PathParam("id") final String id,
                               @QueryParam("timeout") @DefaultValue(CALL_TIMEOUT) Long timeout,
                               @Suspended final AsyncResponse asyncResponse) {
        setTimeout(timeout, asyncResponse);

        HostStoreInfo hostForKey = getKeyLocationOrBlock(id, asyncResponse);

        if (hostForKey == null) { //request timed out so return
            return;
        }
        //Retrieve the order locally or reach out to a different instance if the required partition is hosted elsewhere.
        if (thisHost(hostForKey)) {
            fetchLocal(id, asyncResponse, (k, v) -> true);
        } else {
            String path = new Paths(hostForKey.getHost(), hostForKey.getPort()).urlGet(id);
            fetchFromOtherHost(path, asyncResponse, timeout);
        }
    }

    class FilteredResponse<K, V> {
        private AsyncResponse asyncResponse;
        private Predicate<K, V> predicate;

        FilteredResponse(AsyncResponse asyncResponse, Predicate<K, V> predicate) {
            this.asyncResponse = asyncResponse;
            this.predicate = predicate;
        }
    }

    /**
     * Fetch the order from the local materialized view
     *
     * @param id ID to fetch
     * @param asyncResponse the response to call once completed
     * @param predicate a filter that for this fetch, so for example we might fetch only VALIDATED
     * orders.
     */
    private void fetchLocal(String id, AsyncResponse asyncResponse, Predicate<String, Order> predicate) {
        log.info("running GET on this node");
        try {
            Order order = ordersStore().get(id);
            if (order == null || !predicate.test(id, order)) {
                log.info("Delaying get as order not present for id " + id);
                outstandingRequests.put(id, new FilteredResponse<>(asyncResponse, predicate));
            } else {
                asyncResponse.resume(toBean(order));
            }
        } catch (InvalidStateStoreException e) {
            //Store not ready so delay
            outstandingRequests.put(id, new FilteredResponse<>(asyncResponse, predicate));
        }
    }

    private ReadOnlyKeyValueStore<String, Order> ordersStore() {
        return streams.store(ORDERS_STORE_NAME, QueryableStoreTypes.keyValueStore());
    }

    /**
     * Use Kafka Streams' Queryable State API to work out if a key/value pair is located on
     * this node, or on another Kafka Streams node. This returned HostStoreInfo can be used
     * to redirect an HTTP request to the node that has the data.
     * <p>
     * If metadata is available, which can happen on startup, or during a rebalance, block until it is.
     */
    private HostStoreInfo getKeyLocationOrBlock(String id, AsyncResponse asyncResponse) {
        HostStoreInfo locationOfKey;
        while (locationMetadataIsUnavailable(locationOfKey = getHostForOrderId(id))) {
            //The metastore is not available. This can happen on startup/rebalance.
            if (asyncResponse.isDone()) {
                //The response timed out so return
                return null;
            }
            try {
                //Sleep a bit until metadata becomes available
                Thread.sleep(Math.min(Long.valueOf(CALL_TIMEOUT), 200));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return locationOfKey;
    }

    private boolean locationMetadataIsUnavailable(HostStoreInfo hostWithKey) {
        return NOT_AVAILABLE.host().equals(hostWithKey.getHost())
                && NOT_AVAILABLE.port() == hostWithKey.getPort();
    }

    private boolean thisHost(final HostStoreInfo host) {
        return host.getHost().equals(this.host) &&
                host.getPort() == port;
    }

    private void fetchFromOtherHost(final String path, AsyncResponse asyncResponse, long timeout) {
        log.info("Chaining GET to a different instance: " + path);
        try {
            OrderBean bean = client.target(path)
                    .queryParam("timeout", timeout)
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .get(new GenericType<OrderBean>() {
                    });
            asyncResponse.resume(bean);
        } catch (Exception swallowed) {
        }
    }

    @GET
    @ManagedAsync
    @Path("orders/{id}/validated")
    public void getPostValidationWithTimeout(@PathParam("id") final String id,
                                             @QueryParam("timeout") @DefaultValue(CALL_TIMEOUT) Long timeout,
                                             @Suspended final AsyncResponse asyncResponse) {
        setTimeout(timeout, asyncResponse);

        HostStoreInfo hostForKey = getKeyLocationOrBlock(id, asyncResponse);

        if (hostForKey == null) { //request timed out so return
            return;
        }
        //Retrieve the order locally or reach out to a different instance if the required partition is hosted elsewhere.
        if (thisHost(hostForKey)) {
            fetchLocal(id, asyncResponse,
                    (k, v) -> (v.getState() == OrderState.VALIDATED || v.getState() == OrderState.FAILED));
        } else {
            fetchFromOtherHost(new Paths(hostForKey.getHost(), hostForKey.getPort()).urlGetValidated(id),
                    asyncResponse, timeout);
        }
    }


    /**
     * Persist an Order to Kafka. Returns once the order is successfully written to R nodes where
     * R is the replication factor configured in Kafka.
     *
     * @param order the order to add
     * @param timeout the max time to wait for the response from Kafka before timing out the POST
     */
    @POST
    @ManagedAsync
    @Path("/orders")
    @Consumes(MediaType.APPLICATION_JSON)
    public void submitOrder(final OrderBean order,
                            @QueryParam("timeout") @DefaultValue(CALL_TIMEOUT) final Long timeout,
                            @Suspended final AsyncResponse response) {
        setTimeout(timeout, response);

        Order bean = fromBean(order);

        // TODO 1.1: create a new `ProducerRecord` with a key specified by `bean.getId()` and value of the bean, to the orders topic whose name is specified by `ORDERS.name()`
        // ...

        // TODO 1.2: produce the newly created record using the existing `producer` and pass use the `OrdersService#callback` function to send the `response` and the record key
        // ...

    }

    @SuppressWarnings("unchecked")
    @Override
    public void start(final String bootstrapServers, final String stateDir) {
        jettyServer = startJetty(port, this);
        port = jettyServer.getURI().getPort(); // update port, in case port was zero
        producer = startProducer(bootstrapServers, ORDERS);
        streams = startKStreams(bootstrapServers);
        log.info("Started Service " + getClass().getSimpleName());
    }

    private KafkaStreams startKStreams(String bootstrapServers) {
        KafkaStreams streams = new KafkaStreams(
                createOrdersMaterializedView().build(),
                config(bootstrapServers));
        metadataService = new MetadataService(streams);
        streams.cleanUp(); //don't do this in prod as it clears your state stores
        streams.start();
        return streams;
    }

    private Properties config(String bootstrapServers) {
        Properties props = baseStreamsConfig(bootstrapServers, "/tmp/kafka-streams", SERVICE_APP_ID);
        props.put(StreamsConfig.APPLICATION_SERVER_CONFIG, host + ":" + port);
        return props;
    }

    @Override
    public void stop() {
        if (streams != null) {
            streams.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (jettyServer != null) {
            try {
                jettyServer.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // for testing only
    void cleanLocalState() {
        if (streams != null) {
            streams.cleanUp();
        }
    }

    public int port() {
        return port;
    }

    private HostStoreInfo getHostForOrderId(String orderId) {
        return metadataService
                .streamsMetadataForStoreAndKey(ORDERS_STORE_NAME, orderId, Serdes.String().serializer());
    }

    private Callback callback(final AsyncResponse response, final String orderId) {
        return (recordMetadata, e) -> {
            if (e != null) {
                response.resume(e);
            } else {
                try {
                    //Return the location of the newly created resource
                    Response uri = Response.created(new URI("/v1/orders/" + orderId)).build();
                    response.resume(uri);
                } catch (URISyntaxException e2) {
                    e2.printStackTrace();
                }
            }
        };
    }

    public static void main(String[] args) throws Exception {

        final String bootstrapServers = args.length > 0 ? args[0] : "localhost:9092";
        final String schemaRegistryUrl = args.length > 1 ? args[1] : "http://localhost:8081";
        final String restHostname = args.length > 2 ? args[2] : "localhost";
        final String restPort = args.length > 3 ? args[3] : null;

        Schemas.configureSerdesWithSchemaRegistryUrl(schemaRegistryUrl);
        OrdersService service = new OrdersService(restHostname, restPort == null ? 0 : Integer.valueOf(restPort));
        service.start(bootstrapServers, "/tmp/kafka-streams");
        addShutdownHookAndBlock(service);
    }
}
