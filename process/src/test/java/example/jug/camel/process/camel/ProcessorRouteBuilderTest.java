package example.jug.camel.process.camel;

import static junit.framework.Assert.assertTrue;

import java.sql.SQLException;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.xml.datatype.DatatypeFactory;

import org.apache.camel.CamelContext;
import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.dataformat.JaxbDataFormat;
import org.example.model.ObjectFactory;
import org.example.model.RecordType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import example.jug.camel.logic.NonRecoverableExternalServiceException;
import example.jug.camel.logic.RecoverableExternalServiceException;
import example.jug.camel.model.Record;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration
public class ProcessorRouteBuilderTest {

    @Autowired
    private ProcessorRouteBuilder builder;

    @EndpointInject(uri = "mock:output")
    private MockEndpoint output;

    @EndpointInject(uri = "mock:dlq")
    private MockEndpoint dlq;

    @Produce(uri = "direct:trigger")
    private ProducerTemplate trigger;

    @Autowired
    private CamelContext context;

    @Before
    public void setup() throws Exception {
        
        if (context.getRoute("testRoute") == null) {
            final JaxbDataFormat jbdf = new JaxbDataFormat();
            jbdf.setContextPath(ObjectFactory.class.getPackage().getName());
            
            // Forward requests to the JMS endpoint and monitor the DLQ.
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() throws Exception {
                    from("direct:trigger")
                        .routeId("testRoute")
                        .process(new Processor() {
                            @Override
                            public void process(Exchange exchange)
                                    throws Exception {
                                ObjectFactory objFact = new ObjectFactory();

                                RecordType record = exchange.getIn().getBody(
                                        RecordType.class);
                                exchange.getIn().setBody(
                                        objFact.createRecord(record));
                            }
                        })
                        .marshal(jbdf)
                        // Change ack mode since we don't want Tx with the send
                        .to("activemq:queue:" + builder.getRecordsQueueName()
                                + "?acknowledgementModeName=AUTO_ACKNOWLEDGE");

                    from("activemq:ActiveMQ.DLQ").to("mock:dlq");
                }
            });        
        }
    }
    
    @After
    public void teardown() throws Exception {
        output.reset();
        dlq.reset();
    }

    @Test
    public void testPositive() throws Exception {

        DatatypeFactory dtf = DatatypeFactory.newInstance();

        Set<String> expectedIds = new HashSet<String>();

        output.setExpectedMessageCount(10);
        output.setResultWaitTime(12000l);

        for (int i = 0; i < 10; i++) {
            RecordType recordType = new RecordType();
            recordType.setId(String.valueOf(i));
            recordType.setDate(dtf.newXMLGregorianCalendar(new GregorianCalendar()));
            recordType.setDescription("Record number: " + i);
            expectedIds.add(String.valueOf(i));

            trigger.sendBody(recordType);
        }

        output.assertIsSatisfied();

        for (Exchange exchange : output.getReceivedExchanges()) {
            assertTrue(expectedIds.remove(exchange.getIn()
                    .getBody(Record.class).getId()));
        }

        assertTrue(expectedIds.isEmpty());
    }

    @Test
    @DirtiesContext
    public void testTerminalJdbcFailure() throws Exception {

        configureJdbcFailure(3);

        DatatypeFactory dtf = DatatypeFactory.newInstance();

        Set<String> expectedIds = new HashSet<String>();

        output.setExpectedMessageCount(9);
        output.setResultWaitTime(12000l);

        dlq.setExpectedMessageCount(1);

        for (int i = 0; i < 10; i++) {
            RecordType recordType = new RecordType();
            recordType.setId(String.valueOf(i));
            recordType.setDate(dtf.newXMLGregorianCalendar(new GregorianCalendar()));
            recordType.setDescription("Record number: " + i);

            if (i != 1) {
                expectedIds.add(String.valueOf(i));
            }

            trigger.sendBody(recordType);
        }

        output.assertIsSatisfied();
        dlq.assertIsSatisfied();

        for (Exchange exchange : output.getReceivedExchanges()) {
            assertTrue(expectedIds.remove(exchange.getIn()
                    .getBody(Record.class).getId()));
        }

        assertTrue(expectedIds.isEmpty());

        assertTrue(dlq.getReceivedExchanges().get(0).getIn()
                .getBody(String.class).contains("id>1</"));
    }

    @Test
    @DirtiesContext
    public void testNonTerminalJdbcFailure() throws Exception {

        configureJdbcFailure(1);

        DatatypeFactory dtf = DatatypeFactory.newInstance();

        Set<String> expectedIds = new HashSet<String>();

        output.setExpectedMessageCount(10);
        output.setResultWaitTime(12000l);

        dlq.setExpectedMessageCount(0);

        for (int i = 0; i < 10; i++) {
            RecordType recordType = new RecordType();
            recordType.setId(String.valueOf(i));
            recordType.setDate(dtf
                    .newXMLGregorianCalendar(new GregorianCalendar()));
            recordType.setDescription("Record number: " + i);
            expectedIds.add(String.valueOf(i));

            trigger.sendBody(recordType);
        }

        output.assertIsSatisfied();
        dlq.assertIsSatisfied(10000);

        for (Exchange exchange : output.getReceivedExchanges()) {
            assertTrue(expectedIds.remove(exchange.getIn()
                    .getBody(Record.class).getId()));
        }

        assertTrue(expectedIds.isEmpty());
    }

    @Test
    @DirtiesContext
    public void testRecoverableExternalServiceException() throws Exception {
        configureProcessRecordFailure(3, true);
        
        DatatypeFactory dtf = DatatypeFactory.newInstance();

        output.setExpectedMessageCount(1);

        dlq.setExpectedMessageCount(0);

        RecordType recordType = new RecordType();
        recordType.setId("1");
        recordType.setDate(dtf
                .newXMLGregorianCalendar(new GregorianCalendar()));
        recordType.setDescription("Record number: 1");

        trigger.sendBody(recordType);
        
        output.assertIsSatisfied();
        dlq.assertIsSatisfied(10000);
    }
    
    @Test
    @DirtiesContext
    public void testNonRecoverableExternalServiceException() throws Exception {
        configureProcessRecordFailure(1, false);
        
        DatatypeFactory dtf = DatatypeFactory.newInstance();

        output.setExpectedMessageCount(0);
        dlq.setExpectedMessageCount(1);

        RecordType recordType = new RecordType();
        recordType.setId("1");
        recordType.setDate(dtf
                .newXMLGregorianCalendar(new GregorianCalendar()));
        recordType.setDescription("Record number: 1");

        trigger.sendBody(recordType);
        
        output.assertIsSatisfied(1000);
        dlq.assertIsSatisfied();
    }

    @SuppressWarnings("deprecation")
    protected void configureJdbcFailure(final int failureCount)
            throws Exception {
        RouteDefinition routeDef = context
                .getRouteDefinition(ProcessorRouteBuilder.PERSIST_RECORD_ROUTE_ID);

        routeDef.adviceWith(context, new RouteBuilder() {

            private AtomicInteger count = new AtomicInteger(0);

            @Override
            public void configure() throws Exception {
                interceptSendToEndpoint(
                        builder.getAlternatePersistEndpointUri()).process(
                        new Processor() {

                            @Override
                            public void process(Exchange exchange)
                                    throws Exception {
                                Record record = exchange.getIn().getBody(Record.class);

                                if ("1".equals(record.getId())
                                        && count.getAndIncrement() < failureCount) {
                                    throw new SQLException("Simulated JDBC Error!");
                                }
                            }
                        });
            }
        });
    }

    @SuppressWarnings("deprecation")
    protected void configureProcessRecordFailure(final int failureCount, 
            final boolean recoverableFailure) throws Exception {
        RouteDefinition routeDef = context
                .getRouteDefinition(ProcessorRouteBuilder.PROCESS_RECORD_ROUTE_ID);

        routeDef.adviceWith(context, new RouteBuilder() {

            private AtomicInteger count = new AtomicInteger(0);

            @Override
            public void configure() throws Exception {
                interceptSendToEndpoint(
                        "bean:recordProcessor?method=processRecord").process(
                        new Processor() {

                            @Override
                            public void process(Exchange exchange) throws Exception {
                                Record record = exchange.getIn().getBody(Record.class);

                                if ("1".equals(record.getId())
                                        && count.getAndIncrement() < failureCount) {
                                    if (recoverableFailure) {
                                        throw new RecoverableExternalServiceException(
                                                "Simulated Processor Error!");
                                    } else {
                                        throw new NonRecoverableExternalServiceException(
                                                "Simulated Processor Error!");
                                    }
                                }
                            }
                        });
            }
        });
    }
}
