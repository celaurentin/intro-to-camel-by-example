package example.jug.camel.ingest;

import static junit.framework.Assert.assertTrue;

import java.io.File;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Set;

import javax.jms.JMSException;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.datatype.DatatypeFactory;

import org.apache.camel.CamelContext;
import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.dataformat.JaxbDataFormat;
import org.apache.commons.io.FileUtils;
import org.example.model.AggregateRecordType;
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

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration
public class FileIngestorRouteBuilderTest {
	
	@Autowired
	private FileIngestorRouteBuilder builder;
	
	private File pollingFolder;
	private File doneFolder;
	private File failedFolder;
	
	@EndpointInject(uri = "mock:output")
	private MockEndpoint output;
	
	@Autowired
	private CamelContext context;
	
	@Before
    public void setup() throws Exception {
		
		// Polling folder setup
        pollingFolder = new File(builder.getSourceDirPath());
        FileUtils.deleteDirectory(pollingFolder);
        pollingFolder.mkdirs();
        
        // Done folder setup
        doneFolder = new File(pollingFolder, builder.getDoneDirPath());
        FileUtils.deleteDirectory(doneFolder);
        doneFolder.mkdirs();
        
        // Failed folder setup
        failedFolder = new File(pollingFolder, builder.getFailDirPath());
        FileUtils.deleteDirectory(failedFolder);
        failedFolder.mkdirs();
        
        context.addRoutes(new RouteBuilder() {
        	public void configure() {
        		JaxbDataFormat jbdf = new JaxbDataFormat();
        		jbdf.setContextPath(ObjectFactory.class.getPackage().getName());
        		
        		from(builder.getEnqueueRecordsDestinationUri())
        			.unmarshal(jbdf)
        		    .to(output);
        	}
        });
    }
	
	@After
	public void teardown() throws Exception {
		output.reset();
	}
	
	@Test
	public void testPositive() throws Exception {
		
		DatatypeFactory dtf = DatatypeFactory.newInstance();
        
		Set<String> expectedIds = new HashSet<String>();
        AggregateRecordType agt = new AggregateRecordType();
        agt.setDate(dtf.newXMLGregorianCalendar(new GregorianCalendar()));
        
        output.setExpectedMessageCount(10);
        
        for (int i = 0; i < 10; i++) {
        	RecordType recordType = new RecordType();
        	recordType.setId(String.valueOf(i));
        	recordType.setDate(dtf.newXMLGregorianCalendar(new GregorianCalendar()));
        	recordType.setDescription("Record number: " + i);
        	agt.getRecord().add(recordType);
        	expectedIds.add(String.valueOf(i));
        }
        
        createAndMoveFile(agt);
        
        output.assertIsSatisfied();
        validateFileMove(false);
        
        for (Exchange exchange : output.getReceivedExchanges()) {
        	assertTrue(expectedIds.remove(exchange.getIn().getBody(RecordType.class).getId()));
        }
        
        assertTrue(expectedIds.isEmpty());
	}
	
	@Test
	public void testInvalidSchema() throws Exception {
		// not really atomic, but it works for tests
        FileUtils.moveFile(
        		new File("./target/test-classes/example/jug/camel/ingest/"
        				+ "FilIngestorRouteBuilderTest.testInvalidSchema.xml"),
        		new File(pollingFolder, "test.xml"));
        
        validateFileMove(true);
	}
	
	@SuppressWarnings("deprecation")
	@Test
	@DirtiesContext
	public void testJmsFailure() throws Exception {
		RouteDefinition routeDef = context.getRouteDefinition(
				FileIngestorRouteBuilder.ENQUEUE_RECORD_ROUTE_ID);
		
		routeDef.adviceWith(context, new RouteBuilder() {
			
			@Override
			public void configure() throws Exception {
					interceptSendToEndpoint(builder.getEnqueueRecordsDestinationUri())
						.choice()
							.when().xpath("/example:record/example:id[text() = '1']",
									FileIngestorRouteBuilder.NAMESPACES)
									.throwException(new JMSException("Simulated JMS Error!"))
							.end();
			}
		});
		
		
		DatatypeFactory dtf = DatatypeFactory.newInstance();
        
		Set<String> expectedIds = new HashSet<String>();
        AggregateRecordType agt = new AggregateRecordType();
        agt.setDate(dtf.newXMLGregorianCalendar(new GregorianCalendar()));
        
        output.setExpectedMessageCount(9);
        
        for (int i = 0; i < 10; i++) {
        	RecordType record = new RecordType();
        	record.setId(String.valueOf(i));
        	record.setDate(dtf.newXMLGregorianCalendar(new GregorianCalendar()));
        	record.setDescription("Record number: " + i);
        	agt.getRecord().add(record);
        	
        	if (i != 1) {
        		expectedIds.add(String.valueOf(i));
        	}
        }
        
        createAndMoveFile(agt);
        
        output.assertIsSatisfied();
        validateFileMove(true);
        
        for (Exchange exchange : output.getReceivedExchanges()) {
        	assertTrue(expectedIds.remove(exchange.getIn().getBody(RecordType.class).getId()));
        }
        
        assertTrue(expectedIds.isEmpty());
		
		
	}
	
	protected void createAndMoveFile(AggregateRecordType agt) throws Exception {
        File testFile = new File("./target/test.xml");
        
        if (testFile.exists()) {
            testFile.delete();
        }
        
        ObjectFactory objFact = new ObjectFactory();
        JAXBContext context = JAXBContext.newInstance(ObjectFactory.class);
        Marshaller m = context.createMarshaller();
        m.marshal(objFact.createAggregateRecord(agt), testFile);
        // not really atomic, but it works for tests
        FileUtils.moveFile(testFile, new File(pollingFolder, "test.xml"));
    }
	
	protected void validateFileMove(boolean expectFailure) throws Exception {
        
        File movedFile = new File((expectFailure ? failedFolder : doneFolder), "test.xml");
        
        for (int i = 10; i > 0; i--) {
            if (movedFile.exists()) {
                break;
            }
            Thread.sleep(1000);
        }

        assertTrue(movedFile.exists());
    }
}
