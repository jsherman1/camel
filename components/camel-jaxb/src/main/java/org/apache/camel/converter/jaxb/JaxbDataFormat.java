/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.converter.jaxb;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.ValidationEvent;
import javax.xml.bind.ValidationEventHandler;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.xml.sax.SAXException;

import org.apache.camel.CamelContext;
import org.apache.camel.CamelContextAware;
import org.apache.camel.Exchange;
import org.apache.camel.TypeConverter;
import org.apache.camel.spi.DataFormat;
import org.apache.camel.support.ServiceSupport;
import org.apache.camel.util.CamelContextHelper;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.ResourceHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * A <a href="http://camel.apache.org/data-format.html">data format</a> ({@link DataFormat})
 * using JAXB2 to marshal to and from XML
 *
 * @version 
 */
public class JaxbDataFormat extends ServiceSupport implements DataFormat, CamelContextAware {

    private static final Logger LOG = LoggerFactory.getLogger(JaxbDataFormat.class);
    private static final BlockingQueue<SchemaFactory> SCHEMA_FACTORY_POOL = new LinkedBlockingQueue<SchemaFactory>();

    private SchemaFactory schemaFactory;
    private CamelContext camelContext;
    private JAXBContext context;
    private String contextPath;
    private String schema;
    private boolean prettyPrint = true;
    private boolean ignoreJAXBElement = true;
    private boolean filterNonXmlChars;
    private String encoding;
    private boolean fragment;
    // partial support
    private QName partNamespace;
    private String partClass;
    private Class<Object> partialClass;
    private String namespacePrefixRef;
    private Map<String, String> namespacePrefix;
    private JaxbNamespacePrefixMapper namespacePrefixMapper;
    private JaxbXmlStreamWriterWrapper xmlStreamWriterWrapper;
    private TypeConverter typeConverter;
    private Schema cachedSchema;

    public JaxbDataFormat() {
    }

    public JaxbDataFormat(JAXBContext context) {
        this.context = context;
    }

    public JaxbDataFormat(String contextPath) {
        this.contextPath = contextPath;
    }

    public void marshal(Exchange exchange, Object graph, OutputStream stream) throws IOException, SAXException {
        try {            
            // must create a new instance of marshaller as its not thread safe
            Marshaller marshaller = createMarshaller();
            
            if (isPrettyPrint()) {
                marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            } 
            // exchange take precedence over encoding option
            String charset = exchange.getProperty(Exchange.CHARSET_NAME, String.class);
            if (charset == null) {
                charset = encoding;
            }
            if (charset != null) {
                marshaller.setProperty(Marshaller.JAXB_ENCODING, charset);
            }
            if (isFragment()) {
                marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
            }
            if (namespacePrefixMapper != null) {
                marshaller.setProperty(namespacePrefixMapper.getRegistrationKey(), namespacePrefixMapper);
            }

            marshal(exchange, graph, stream, marshaller);

        } catch (JAXBException e) {
            throw new IOException(e);
        } catch (XMLStreamException e) {
            throw new IOException(e);
        }
    }

    void marshal(Exchange exchange, Object graph, OutputStream stream, Marshaller marshaller)
        throws XMLStreamException, JAXBException {

        Object e = graph;
        if (partialClass != null && getPartNamespace() != null) {
            e = new JAXBElement<Object>(getPartNamespace(), partialClass, graph);
        }

        if (asXmlStreamWriter(exchange)) {
            XMLStreamWriter writer = typeConverter.convertTo(XMLStreamWriter.class, stream);
            if (needFiltering(exchange)) {
                writer = new FilteringXmlStreamWriter(writer);
            }
            if (xmlStreamWriterWrapper != null) {
                writer = xmlStreamWriterWrapper.wrapWriter(writer);
            }
            marshaller.marshal(e, writer);
        } else {
            marshaller.marshal(e, stream);
        }
    }

    private boolean asXmlStreamWriter(Exchange exchange) {
        return needFiltering(exchange) || (xmlStreamWriterWrapper != null);
    }

    public Object unmarshal(Exchange exchange, InputStream stream) throws IOException, SAXException {
        try {
            Object answer;

            XMLStreamReader xmlReader;
            if (needFiltering(exchange)) {
                xmlReader = typeConverter.convertTo(XMLStreamReader.class, createNonXmlFilterReader(exchange, stream));
            } else {
                xmlReader = typeConverter.convertTo(XMLStreamReader.class, stream);
            }
            if (partialClass != null) {
                // partial unmarshalling
                answer = createUnmarshaller().unmarshal(xmlReader, partialClass);
            } else {
                answer = createUnmarshaller().unmarshal(xmlReader);
            }

            if (answer instanceof JAXBElement && isIgnoreJAXBElement()) {
                answer = ((JAXBElement<?>)answer).getValue();
            }
            return answer;
        } catch (JAXBException e) {
            throw new IOException(e);
        }
    }

    private NonXmlFilterReader createNonXmlFilterReader(Exchange exchange, InputStream stream) throws UnsupportedEncodingException {
        return new NonXmlFilterReader(new InputStreamReader(stream, IOHelper.getCharsetName(exchange)));
    }

    protected boolean needFiltering(Exchange exchange) {
        // exchange property takes precedence over data format property
        return exchange == null ? filterNonXmlChars : exchange.getProperty(Exchange.FILTER_NON_XML_CHARS, filterNonXmlChars, Boolean.class);
    }

    // Properties
    // -------------------------------------------------------------------------
    public boolean isIgnoreJAXBElement() {        
        return ignoreJAXBElement;
    }
    
    public void setIgnoreJAXBElement(boolean flag) {
        ignoreJAXBElement = flag;
    }
    
    public JAXBContext getContext() {
        return context;
    }

    public void setContext(JAXBContext context) {
        this.context = context;
    }

    public String getContextPath() {
        return contextPath;
    }

    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
    }

    public SchemaFactory getSchemaFactory() {
        if (schemaFactory == null) {
            return getOrCreateSchemaFactory();
        }
        return schemaFactory;
    }

    public void setSchemaFactory(SchemaFactory schemaFactory) {
        this.schemaFactory = schemaFactory;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public boolean isPrettyPrint() {
        return prettyPrint;
    }

    public void setPrettyPrint(boolean prettyPrint) {
        this.prettyPrint = prettyPrint;
    }
    
    public boolean isFragment() {
        return fragment;
    }
    
    public void setFragment(boolean fragment) {
        this.fragment = fragment;
    }

    public boolean isFilterNonXmlChars() {
        return filterNonXmlChars;
    }

    public void setFilterNonXmlChars(boolean filterNonXmlChars) {
        this.filterNonXmlChars = filterNonXmlChars;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public QName getPartNamespace() {
        return partNamespace;
    }

    public void setPartNamespace(QName partNamespace) {
        this.partNamespace = partNamespace;
    }

    public String getPartClass() {
        return partClass;
    }

    public void setPartClass(String partClass) {
        this.partClass = partClass;
    }

    public Map<String, String> getNamespacePrefix() {
        return namespacePrefix;
    }

    public void setNamespacePrefix(Map<String, String> namespacePrefix) {
        this.namespacePrefix = namespacePrefix;
    }

    public String getNamespacePrefixRef() {
        return namespacePrefixRef;
    }

    public void setNamespacePrefixRef(String namespacePrefixRef) {
        this.namespacePrefixRef = namespacePrefixRef;
    }

    public CamelContext getCamelContext() {
        return camelContext;
    }

    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    public JaxbXmlStreamWriterWrapper getXmlStreamWriterWrapper() {
        return xmlStreamWriterWrapper;
    }

    public void setXmlStreamWriterWrapper(JaxbXmlStreamWriterWrapper xmlStreamWriterWrapper) {
        this.xmlStreamWriterWrapper = xmlStreamWriterWrapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void doStart() throws Exception {
        ObjectHelper.notNull(camelContext, "CamelContext");

        if (context == null) {
            // if context not injected, create one and resolve partial class up front so they are ready to be used
            context = createContext();
        }
        if (partClass != null) {
            partialClass = camelContext.getClassResolver().resolveMandatoryClass(partClass, Object.class);
        }
        if (namespacePrefixRef != null) {
            namespacePrefix = CamelContextHelper.mandatoryLookup(camelContext, namespacePrefixRef, Map.class);
        }
        if (namespacePrefix != null) {
            namespacePrefixMapper = NamespacePrefixMapperFactory.newNamespacePrefixMapper(camelContext, namespacePrefix);
        }
        typeConverter = camelContext.getTypeConverter();
        if (schema != null) {
            cachedSchema = createSchema(getSources());
        }
    }

    @Override
    protected void doStop() throws Exception {
    }

    /**
     * Strategy to create JAXB context
     */
    protected JAXBContext createContext() throws JAXBException {
        if (contextPath != null) {
            // prefer to use application class loader which is most likely to be able to
            // load the the class which has been JAXB annotated
            ClassLoader cl = camelContext.getApplicationContextClassLoader();
            if (cl != null) {
                LOG.info("Creating JAXBContext with contextPath: " + contextPath + " and ApplicationContextClassLoader: " + cl);
                return JAXBContext.newInstance(contextPath, cl);
            } else {
                LOG.info("Creating JAXBContext with contextPath: " + contextPath);
                return JAXBContext.newInstance(contextPath);
            }
        } else {
            LOG.info("Creating JAXBContext");
            return JAXBContext.newInstance();
        }
    }
    
    protected Unmarshaller createUnmarshaller() throws JAXBException, SAXException, FileNotFoundException,
        MalformedURLException {
        Unmarshaller unmarshaller = getContext().createUnmarshaller();
        if (schema != null) {
            unmarshaller.setSchema(cachedSchema);
            unmarshaller.setEventHandler(new ValidationEventHandler() {
                public boolean handleEvent(ValidationEvent event) {
                    // stop unmarshalling if the event is an ERROR or FATAL
                    // ERROR
                    return event.getSeverity() == ValidationEvent.WARNING;
                }
            });

        }

        return unmarshaller;
    }

    protected Marshaller createMarshaller() throws JAXBException, SAXException, FileNotFoundException,
        MalformedURLException {
        Marshaller marshaller = getContext().createMarshaller();
        if (schema != null) {
            marshaller.setSchema(cachedSchema);
            marshaller.setEventHandler(new ValidationEventHandler() {
                public boolean handleEvent(ValidationEvent event) {
                    // stop marshalling if the event is an ERROR or FATAL ERROR
                    return event.getSeverity() == ValidationEvent.WARNING;
                }
            });

        }

        return marshaller;
    }
    
    private Schema createSchema(Source[] sources) throws SAXException {
        SchemaFactory factory = getOrCreateSchemaFactory();
        try {
            return factory.newSchema(sources);
        } finally {
            returnSchemaFactory(factory);
        }
    }

    private Source[] getSources() throws FileNotFoundException, MalformedURLException {
        // we support multiple schema by delimiting they by ','
        String[] schemas = schema.split(",");
        Source[] sources = new Source[schemas.length];
        for (int i = 0; i < schemas.length; i++) {
            URL schemaUrl = ResourceHelper.resolveMandatoryResourceAsUrl(camelContext.getClassResolver(), schemas[i]);
            sources[i] = new StreamSource(schemaUrl.toExternalForm());
        }
        return sources;
    }

    private SchemaFactory getOrCreateSchemaFactory() {
        SchemaFactory factory = SCHEMA_FACTORY_POOL.poll();
        if (factory == null) {
            factory = createSchemaFactory();
        }
        return factory;
    }

    public static SchemaFactory createSchemaFactory() {
        return SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    }

    private void returnSchemaFactory(SchemaFactory factory) {
        if (factory != schemaFactory) {
            SCHEMA_FACTORY_POOL.offer(factory);
        }
    }
}
