package ut;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.Map;
import java.util.Properties;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Named;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ibm.gse.eda.stores.domain.ItemTransaction;
import ibm.gse.eda.stores.infra.events.StoreSerdes;

/**
 * Route input message with data issue to a dead letter topic
 */
public class TestDeadLetterTopic {

    private static TopologyTestDriver testDriver;
    private static TestInputTopic<String, ItemTransaction> inputTopic;
    private static TestOutputTopic<String, ItemTransaction> outputTopic;
    private static String inTopicName = "my-input-topic";
    private static String outTopicName = "my-output-topic";
    private  static TestOutputTopic<String, ItemTransaction> dlTopic;
    private static String deadLetterTopicName = "dl-topic";
    // 0 add dead letter topic  

    /**
     * Using split and branches to separate good from wrong records
     * 
     * @return Kafka Streams topology
     */
    public static Topology buildTopologyFlow() {

        final StreamsBuilder builder = new StreamsBuilder();

        // 1- get the input stream
        KStream<String, ItemTransaction> items = builder.stream(inTopicName,
                Consumed.with(Serdes.String(), StoreSerdes.ItemTransactionSerde()));
        // 2 build branches
        Map<String, KStream<String, ItemTransaction>> branches = items.split(Named.as("B-"))
                .branch((k, v) -> (v.storeName == null || v.storeName.isEmpty() || v.sku == null || v.sku.isEmpty()),
                        Branched.as("wrong-tx"))
                .defaultBranch(Branched.as("good-tx"));
        // Generate to output topic
        branches.get("B-good-tx").to(outTopicName);
        branches.get("B-wrong-tx").to(deadLetterTopicName);
        items.peek((key, value) -> System.out.println("PRE-BRANCH: key=" + key + ", value=" + value));
        KStream<String, String> defaultTopic = builder.stream(outTopicName,Consumed.with(Serdes.String(), Serdes.String()));
        defaultTopic.peek((key, value) -> System.out.println("POST-BRANCH-DEFAULT-TOPIC: key=" + key + ", value=" + value));
        KStream<String, String> dlTopic = builder.stream(deadLetterTopicName,Consumed.with(Serdes.String(), Serdes.String()));
        dlTopic.peek((key, value) -> System.out.println("POST-BRANCH-DEAD-LETTER-TOPIC: key=" + key + ", value=" + value));

        return builder.build();
    }

    @BeforeAll
    public static void setup() {
        Topology topology = buildTopologyFlow();
        System.out.println(topology.describe());
        testDriver = new TopologyTestDriver(topology, getStreamsConfig());
        inputTopic = testDriver.createInputTopic(inTopicName, new StringSerializer(),
                StoreSerdes.ItemTransactionSerde().serializer());
        outputTopic = testDriver.createOutputTopic(outTopicName, new StringDeserializer(),
                StoreSerdes.ItemTransactionSerde().deserializer());
        // 4 create the output dead letter topic to test record
        dlTopic = testDriver.createOutputTopic(deadLetterTopicName, new StringDeserializer(),  StoreSerdes.ItemTransactionSerde().deserializer());
        StoreSerdes.ItemTransactionSerde().deserializer();
    }

    public static Properties getStreamsConfig() {
        final Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "kstream-labs");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummmy:1234");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, StoreSerdes.ItemTransactionSerde().getClass());
        return props;
    }

    @AfterAll
    public static void tearDown() {
        try {
            testDriver.close();
        } catch (final Exception e) {
            System.out.println("Ignoring exception, test failing due this exception:" + e.getLocalizedMessage());
        }
    }

    /**
     * If we do not send message to the input topic there is no message to the
     * output topic.
     */
    @Test
    public void isEmpty() {
        assertThat(outputTopic.isEmpty(), is(true));
    }

    @Test
    public void sendValidRecord() {
        ItemTransaction item = new ItemTransaction("Store-1", "Item-1", ItemTransaction.RESTOCK, 5, 33.2);
        inputTopic.pipeInput(item.storeName, item);
        assertThat(outputTopic.getQueueSize(), equalTo(1L));
        ItemTransaction filteredItem = outputTopic.readValue();
        assertThat(filteredItem.storeName, equalTo("Store-1"));
    }

    @Test
    public void nullStoreNameRecordShouldGetNoOutputMessageButDeadLetterMessage() {
        ItemTransaction item = new ItemTransaction(null, "Item-1", ItemTransaction.RESTOCK, 5, 33.2);
        inputTopic.pipeInput(item.storeName, item);
        assertThat(outputTopic.isEmpty(), is(true));
        assertThat(dlTopic.isEmpty(), is(false));
        ItemTransaction filteredItem = dlTopic.readValue();
        assertThat(filteredItem.sku, equalTo("Item-1"));
    }

    @Test
    public void emptyStoreNameRecordShouldGetNoOutputMessage() {
        ItemTransaction item = new ItemTransaction("", "Item-1", ItemTransaction.RESTOCK, 5, 33.2);
        inputTopic.pipeInput(item.storeName, item);
        assertThat(outputTopic.isEmpty(), is(true));
        assertThat(dlTopic.isEmpty(), is(false));
        ItemTransaction filteredItem = dlTopic.readValue();
        assertThat(filteredItem.sku, equalTo("Item-1"));
    }

    @Test
    public void nullSkuRecordShouldGetNoOutputMessage() {
        // assertThat(outputTopic.getQueueSize(), equalTo(0L) );

        ItemTransaction item = new ItemTransaction("Store-1", null, ItemTransaction.RESTOCK, 5, 33.2);
        inputTopic.pipeInput(item.storeName, item);
        assertThat(outputTopic.isEmpty(), is(true));
        assertThat(dlTopic.isEmpty(), is(false));
        ItemTransaction filteredItem = dlTopic.readValue();
        assertThat(filteredItem.storeName, equalTo("Store-1"));
    }

    @Test
    public void emptySkuRecordShouldGetNoOutputMessage() {
        ItemTransaction item = new ItemTransaction("Store-1", "", ItemTransaction.RESTOCK, 5, 33.2);
        inputTopic.pipeInput(item.storeName, item);
        assertThat(outputTopic.isEmpty(), is(true));
        assertThat(dlTopic.isEmpty(), is(false));
        ItemTransaction filteredItem = dlTopic.readValue();
        assertThat(filteredItem.sku, equalTo(""));
    }
}
