package davmail.exchange;

import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.HeadMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.util.URIUtil;
import org.apache.webdav.lib.Property;
import org.apache.webdav.lib.ResponseEntity;
import org.apache.webdav.lib.WebdavResource;
import org.apache.log4j.Logger;
import org.jdom.Attribute;
import org.jdom.JDOMException;
import org.jdom.input.DOMBuilder;
import org.w3c.tidy.Tidy;

import javax.mail.MessagingException;
import javax.mail.internet.MimeUtility;
import java.io.*;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Exchange session through Outlook Web Access (DAV)
 */
public class ExchangeSession {
    protected static final Logger logger = Logger.getLogger("davmail.exchange.ExchangeSession");

    /**
     * exchange message properties needed to rebuild mime message
     */
    protected static final Vector<String> messageRequestProperties = new Vector<String>();

    static {
        messageRequestProperties.add("DAV:uid");
        messageRequestProperties.add("urn:schemas:httpmail:subject");
        messageRequestProperties.add("urn:schemas:mailheader:mime-version");
        messageRequestProperties.add("urn:schemas:mailheader:content-class");
        messageRequestProperties.add("urn:schemas:httpmail:hasattachment");

        // needed only when full headers not found
        messageRequestProperties.add("urn:schemas:mailheader:received");
        messageRequestProperties.add("urn:schemas:mailheader:date");
        messageRequestProperties.add("urn:schemas:mailheader:message-id");
        messageRequestProperties.add("urn:schemas:mailheader:thread-topic");
        messageRequestProperties.add("urn:schemas:mailheader:thread-index");
        messageRequestProperties.add("urn:schemas:mailheader:from");
        messageRequestProperties.add("urn:schemas:mailheader:to");
        messageRequestProperties.add("urn:schemas:httpmail:priority");

        // full headers
        messageRequestProperties.add("http://schemas.microsoft.com/mapi/proptag/x0007D001E");
        // mail body
        messageRequestProperties.add("http://schemas.microsoft.com/mapi/proptag/x01000001E");
        // html body
        messageRequestProperties.add("urn:schemas:httpmail:htmldescription");
        // same as htmldescription, remove
        // messageRequestProperties.add("http://schemas.microsoft.com/mapi/proptag/x01013001E");
        // size
        messageRequestProperties.add("http://schemas.microsoft.com/mapi/proptag/x0e080003");
        // only for calendar events
        messageRequestProperties.add("urn:schemas:calendar:location");
        messageRequestProperties.add("urn:schemas:calendar:dtstart");
        messageRequestProperties.add("urn:schemas:calendar:dtend");
        messageRequestProperties.add("urn:schemas:calendar:instancetype");
        messageRequestProperties.add("urn:schemas:calendar:busystatus");
        messageRequestProperties.add("urn:schemas:calendar:meetingstatus");
        messageRequestProperties.add("urn:schemas:calendar:alldayevent");
        messageRequestProperties.add("urn:schemas:calendar:responserequested");
        // TODO : full headers rebuild with cc
        messageRequestProperties.add("urn:schemas:mailheader:cc");

    }

    public static HashMap<String, String> priorities = new HashMap<String, String>();

    static {
        priorities.put("-2", "5 (Lowest)");
        priorities.put("-1", "4 (Low)");
        priorities.put("1", "2 (High)");
        priorities.put("2", "1 (Highest)");
    }

    public static final String CONTENT_TYPE_HEADER = "content-type: ";
    public static final String CONTENT_TRANSFER_ENCODING_HEADER = "content-transfer-encoding: ";

    /**
     * Date parser from Exchange format
     */
    public final SimpleDateFormat dateParser;
    /**
     * Date formatter to MIME format
     */
    public final SimpleDateFormat dateFormatter;

    protected String url;

    /**
     * Various standard mail boxes Urls
     */
    protected String inboxUrl;
    protected String deleteditemsUrl;
    protected String sendmsgUrl;
    protected String draftsUrl;

    /**
     * Base user mailboxes path (used to select folder)
     */
    protected String mailPath;
    protected String currentFolderUrl;
    WebdavResource wdr = null;

    /**
     * Create an exchange session for the given URL.
     * The session is not actually established until a call to login()
     *
     * @param url Outlook Web Access URL
     */
    public ExchangeSession(String url) {
        this.url = url;
        // SimpleDateFormat are not thread safe, need to create one instance for
        // each session
        dateParser = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
        dateParser.setTimeZone(new SimpleTimeZone(0, "GMT"));
        dateFormatter = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
    }

    public void login(String userName, String password) throws Exception {
        try {
            // TODO : support different ports
            URL urlObject = new URL(url);
            // webdavresource is unable to create the correct url type
            HttpURL httpURL;
            if (url.startsWith("http://")) {
                httpURL = new HttpURL(userName, password,
                        urlObject.getHost(), urlObject.getPort());
            } else if (url.startsWith("https://")) {
                httpURL = new HttpsURL(userName, password,
                        urlObject.getHost(), urlObject.getPort());
            } else {
                throw new IllegalArgumentException("Invalid URL: " + url);
            }
            wdr = new WebdavResource(httpURL, WebdavResource.NOACTION, 0);

            // set httpclient timeout to 30 seconds
            //wdr.retrieveSessionInstance().setTimeout(30000);

            // get proxy configuration from system properties
            String proxyHost = System.getProperty("http.proxyHost");
            String proxyPort = System.getProperty("http.proxyPort");
            String proxyUser = System.getProperty("http.proxyUser");
            String proxyPassword = System.getProperty("http.proxyPassword");

            // get the internal HttpClient instance
            HttpClient httpClient = wdr.retrieveSessionInstance();

/*          // Only available in newer HttpClient releases, not compatible with slide library
            List authPrefs = new ArrayList();
            authPrefs.add(AuthPolicy.BASIC);
            httpClient.getParams().setParameter(AuthPolicy.AUTH_SCHEME_PRIORITY,authPrefs);
*/
            // do not send basic auth automatically
            httpClient.getState().setAuthenticationPreemptive(false);

            // configure proxy
            if (proxyHost != null) {
                httpClient.getHostConfiguration().setProxy(proxyHost, Integer.parseInt(proxyPort));
                if (proxyUser != null) {
                    // detect ntlm authentication (windows domain name in user name)
                    int backslashindex = proxyUser.indexOf("\\");
                    if (backslashindex > 0) {
                        httpClient.getState().setProxyCredentials(null, proxyHost,
                                new NTCredentials(proxyUser.substring(backslashindex + 1),
                                        proxyPassword, null,
                                        proxyUser.substring(0, backslashindex)));
                    } else {
                        httpClient.getState().setProxyCredentials(null, proxyHost,
                                new UsernamePasswordCredentials(proxyUser, proxyPassword));
                    }
                }
            }

            // get webmail root url (will follow redirects)
            // providing credentials
            HttpMethod initmethod = new GetMethod(url);
            wdr.executeHttpRequestMethod(httpClient,
                    initmethod);
            if (initmethod.getPath().indexOf("exchweb/bin") > 0) {
                logger.debug("** Form based authentication detected");

                PostMethod logonMethod = new PostMethod(
                        "/exchweb/bin/auth/owaauth.dll?" +
                                "ForcedBasic=false&Basic=false&Private=true" +
                                "&Language=No_Value"
                );
                logonMethod.addParameter("destination", url);
                logonMethod.addParameter("flags", "4");
//                logonMethod.addParameter("visusername", userName.substring(userName.lastIndexOf('\\')));
                logonMethod.addParameter("username", userName);
                logonMethod.addParameter("password", password);
//                logonMethod.addParameter("SubmitCreds", "Log On");
//                logonMethod.addParameter("forcedownlevel", "0");
                logonMethod.addParameter("trusted", "4");

                wdr.executeHttpRequestMethod(wdr.retrieveSessionInstance(),
                        logonMethod);
                Header locationHeader = logonMethod.getResponseHeader(
                        "Location");

                if (logonMethod.getStatusCode() != 302 ||
                        locationHeader == null ||
                        !url.equals(locationHeader.getValue())) {
                    throw new HttpException("Authentication failed");
                }

            }

            // User now authenticated, get various session information
            HttpMethod method = new GetMethod(url);
            int status = wdr.executeHttpRequestMethod(wdr.
                    retrieveSessionInstance(), method);
            if (status != HttpStatus.SC_MULTI_STATUS
                    && status != HttpStatus.SC_OK) {
                HttpException ex = new HttpException();
                ex.setReasonCode(status);
                throw ex;
            }

            // TODO : catch exception
            // get user mail URL from html body (multi frame)
            String body = method.getResponseBodyAsString();
            int beginIndex = body.indexOf(url);
            if (beginIndex < 0) {
                throw new HttpException(url + "not found in body");
            }
            body = body.substring(beginIndex);
            int endIndex = body.indexOf('"');
            if (endIndex < 0) {
                throw new HttpException(url + "not found in body");
            }
            body = body.substring(url.length(), endIndex);
            // got base http mailbox http url
            mailPath = "/exchange/" + body;
            wdr.setPath(mailPath);
//            wdr.propfindMethod(0);

            // Retrieve inbox and trash URLs
            Vector<String> reqProps = new Vector<String>();
            reqProps.add("urn:schemas:httpmail:inbox");
            reqProps.add("urn:schemas:httpmail:deleteditems");
            reqProps.add("urn:schemas:httpmail:sendmsg");
            reqProps.add("urn:schemas:httpmail:drafts");

            Enumeration inboxEnum = wdr.propfindMethod(0, reqProps);
            if (!inboxEnum.hasMoreElements()) {
                throw new IOException("Unable to get inbox");
            }
            ResponseEntity inboxResponse = (ResponseEntity) inboxEnum.
                    nextElement();
            Enumeration inboxPropsEnum = inboxResponse.getProperties();
            if (!inboxPropsEnum.hasMoreElements()) {
                throw new IOException("Unable to get inbox");
            }
            while (inboxPropsEnum.hasMoreElements()) {
                Property inboxProp = (Property) inboxPropsEnum.nextElement();
                if ("inbox".equals(inboxProp.getLocalName())) {
                    inboxUrl = URIUtil.decode(inboxProp.getPropertyAsString());
                }
                if ("deleteditems".equals(inboxProp.getLocalName())) {
                    deleteditemsUrl = URIUtil.decode(inboxProp.
                            getPropertyAsString());
                }
                if ("sendmsg".equals(inboxProp.getLocalName())) {
                    sendmsgUrl = URIUtil.decode(inboxProp.
                            getPropertyAsString());
                }
                if ("drafts".equals(inboxProp.getLocalName())) {
                    draftsUrl = URIUtil.decode(inboxProp.
                            getPropertyAsString());
                }
            }

            // set current folder to Inbox
            currentFolderUrl = inboxUrl;

            logger.debug("Inbox URL : " + inboxUrl);
            logger.debug("Trash URL : " + deleteditemsUrl);
            logger.debug("Send URL : " + sendmsgUrl);
            deleteditemsUrl = URIUtil.getPath(deleteditemsUrl);
            wdr.setPath(URIUtil.getPath(inboxUrl));

        } catch (Exception exc) {
            logger.error("Exchange login exception ", exc);
            try {
                System.err.println(
                        wdr.getStatusCode() + " " + wdr.getStatusMessage());
            } catch (Exception e) {
                logger.error("Exception getting status from " + wdr);
            }
            throw exc;
        }

    }

    /**
     * Close session.
     * This will only close http client, not the actual Exchange session
     *
     * @throws IOException
     */
    public void close() throws IOException {
        wdr.close();
    }

    /**
     * Create message in current folder
     */
    public void createMessage(String subject, String messageBody) throws IOException {
        createMessage(currentFolderUrl, subject, messageBody);
    }

    /**
     * Create message in specified folder.
     * Will overwrite an existing message with same subject in the same folder
     */
    public void createMessage(String folderUrl, String subject, String messageBody) throws IOException {
        String messageUrl = URIUtil.encodePathQuery(folderUrl + "/" + subject + ".EML");

        PutMethod putmethod = new PutMethod(messageUrl);
        putmethod.setRequestHeader("Content-Type", "message/rfc822");
        putmethod.setRequestBody(messageBody);

        int code = wdr.retrieveSessionInstance().executeMethod(putmethod);

        if (code == 200) {
            logger.warn("Overwritten message " + messageUrl);
        } else if (code != 201) {
            throw new IOException("Unable to create message " + code + " " + putmethod.getStatusLine());
        }
    }

    protected Message buildMessage(ResponseEntity responseEntity) throws URIException {
        Message message = new Message();
        message.messageUrl = URIUtil.decode(responseEntity.getHref());
        Enumeration propertiesEnum = responseEntity.getProperties();
        while (propertiesEnum.hasMoreElements()) {
            Property prop = (Property) propertiesEnum.nextElement();
            String localName = prop.getLocalName();
            if ("x0007D001E".equals(localName)) {
                message.fullHeaders = prop.getPropertyAsString();
            } else if ("x01000001E".equals(localName)) {
                message.body = prop.getPropertyAsString();
            } else if ("x0e080003".equals(localName)) {
                message.size = Integer.parseInt(prop.getPropertyAsString());
            } else if ("htmldescription".equals(localName)) {
                message.htmlBody = prop.getPropertyAsString();
            } else if ("uid".equals(localName)) {
                message.uid = prop.getPropertyAsString();
            } else if ("content-class".equals(prop.getLocalName())) {
                message.contentClass = prop.getPropertyAsString();
            } else if (("hasattachment").equals(prop.getLocalName())) {
                message.hasAttachment = "1".equals(prop.getPropertyAsString());
            } else if ("received".equals(prop.getLocalName())) {
                message.received = prop.getPropertyAsString();
            } else if ("date".equals(prop.getLocalName())) {
                message.date = prop.getPropertyAsString();
            } else if ("message-id".equals(prop.getLocalName())) {
                message.messageId = prop.getPropertyAsString();
            } else if ("thread-topic".equals(prop.getLocalName())) {
                message.threadTopic = prop.getPropertyAsString();
            } else if ("thread-index".equals(prop.getLocalName())) {
                message.threadIndex = prop.getPropertyAsString();
            } else if ("from".equals(prop.getLocalName())) {
                message.from = prop.getPropertyAsString();
            } else if ("to".equals(prop.getLocalName())) {
                message.to = prop.getPropertyAsString();
            } else if ("subject".equals(prop.getLocalName())) {
                message.subject = prop.getPropertyAsString();
            } else if ("priority".equals(prop.getLocalName())) {
                String priorityLabel = priorities.get(prop.getPropertyAsString());
                if (priorityLabel != null) {
                    message.priority = priorityLabel;
                }
            }
        }
        message.preProcessHeaders();


        return message;
    }

    public Message getMessage(String messageUrl) throws IOException {

        // // TODO switch according to Log4J log level

        wdr.setDebug(4);
        wdr.propfindMethod(messageUrl, 0);

        Enumeration messageEnum = wdr.propfindMethod(messageUrl, 0, messageRequestProperties);
        wdr.setDebug(0);

        // 201 created in some cases ?!?
        if ((wdr.getStatusCode() != 200 && wdr.getStatusCode() != 201) || !messageEnum.hasMoreElements()) {
            throw new IOException("Unable to get message: " + wdr.getStatusCode()
                    + " " + wdr.getStatusMessage());
        }
        ResponseEntity entity = (ResponseEntity) messageEnum.nextElement();

        return buildMessage(entity);

    }

    public List<Message> getAllMessages() throws IOException {
        List<Message> messages = new ArrayList<Message>();
        wdr.setDebug(4);
        wdr.propfindMethod(currentFolderUrl, 1);

        Enumeration folderEnum = wdr.propfindMethod(currentFolderUrl, 1, messageRequestProperties);
        wdr.setDebug(0);
        while (folderEnum.hasMoreElements()) {
            ResponseEntity entity = (ResponseEntity) folderEnum.nextElement();

            Message message = buildMessage(entity);
            if ("urn:content-classes:message".equals(message.contentClass) ||
                    "urn:content-classes:calendarmessage".equals(message.contentClass)) {
                messages.add(message);
            }
        }
        return messages;
    }

    public void sendMessage(BufferedReader reader) throws IOException {
        String subject = "davmailtemp";
        String line = reader.readLine();
        // TODO : use reader directly instead of buffer
        StringBuffer mailBuffer = new StringBuffer();
        while (!".".equals(line)) {
            mailBuffer.append(line);
            mailBuffer.append("\n");
            line = reader.readLine();

            // patch thunderbird html in reply for correct outlook display
            if (line.startsWith("  <meta content=\"text/html")) {
                line += "\n  <style> blockquote { display: block; margin: 1em 0px; padding-left: 1em; border-left: solid; border-color: blue; border-width: thin;}</style>";
            }
            if (line.startsWith("Subject")) {
                subject = MimeUtility.decodeText(line.substring(8).trim());
                // '/' is invalid as message URL
                subject = subject.replaceAll("/", "_xF8FF_");
                // '?' is also invalid
                subject = subject.replaceAll("\\?", "");
            }
        }

        createMessage(draftsUrl, subject, mailBuffer.toString());

        // warning : slide library expects *unencoded* urls
        String tempUrl = draftsUrl + "/" + subject + ".eml";
        boolean sent = wdr.moveMethod(tempUrl, sendmsgUrl);
        if (!sent) {
            throw new IOException("Unable to send message: " + wdr.getStatusCode()
                    + " " + wdr.getStatusMessage());
        }

    }

    public Folder selectFolder(String folderName) throws IOException {
        Folder folder = new Folder();
        folder.folderUrl = null;
        if ("INBOX".equals(folderName)) {
            folder.folderUrl = inboxUrl;
        } else {
            folder.folderUrl = mailPath + folderName;
        }

        Vector<String> reqProps = new Vector<String>();
        reqProps.add("urn:schemas:httpmail:unreadcount");
        reqProps.add("DAV:childcount");
        Enumeration folderEnum = wdr.propfindMethod(folder.folderUrl, 0, reqProps);
        if (folderEnum.hasMoreElements()) {
            ResponseEntity entity = (ResponseEntity) folderEnum.nextElement();
            Enumeration propertiesEnum = entity.getProperties();
            while (propertiesEnum.hasMoreElements()) {
                Property prop = (Property) propertiesEnum.nextElement();
                if ("unreadcount".equals(prop.getLocalName())) {
                    folder.unreadCount = Integer.parseInt(prop.getPropertyAsString());
                }
                if ("childcount".equals(prop.getLocalName())) {
                    folder.childCount = Integer.parseInt(prop.getPropertyAsString());
                }
            }

        } else {
            throw new IOException("Folder not found :" + folder.folderUrl);
        }
        currentFolderUrl = folder.folderUrl;
        return folder;
    }

    public class Folder {
        public String folderUrl;
        public int childCount;
        public int unreadCount;
    }

    public class Message {
        public static final String CONTENT_TYPE_HEADER = "Content-Type: ";
        public static final String CONTENT_TRANSFER_ENCODING_HEADER = "Content-Transfer-Encoding: ";
        public String messageUrl;
        public String uid;
        public int size;
        public String fullHeaders;
        public String body;
        public String htmlBody;
        public String contentClass;
        public boolean hasAttachment;
        public String to;
        public String date;
        public String messageId;
        public String received;
        public String threadIndex;
        public String threadTopic;
        public String from;
        public String subject;
        public String priority;

        protected Map<String, String> attachmentsMap;

        // attachment index used during write
        protected int attachmentIndex;

        protected String getReceived() {
            StringTokenizer st = new StringTokenizer(received, "\r\n");
            StringBuffer result = new StringBuffer();
            while (st.hasMoreTokens()) {
                result.append("Received: ").append(st.nextToken()).append("\r\n");
            }
            return result.toString();
        }

        protected void preProcessHeaders() {
            // only handle exchange messages
            // TODO : handle calendar messages
            if (!"urn:content-classes:message".equals(contentClass) &&
                    !"urn:content-classes:calendarmessage".equals(contentClass)
                    ) {
                return;
            }

            // fullHeaders seem empty sometimes, rebuild it
            if (fullHeaders == null || fullHeaders.length() == 0) {
                try {
                    if (date.length() > 0) {
                        Date parsedDate = dateParser.parse(date);
                        date = dateFormatter.format(parsedDate);
                    }
                    fullHeaders = "Skipped header\n" + getReceived() +
                            "MIME-Version: 1.0\n" +
                            "Content-Type: application/ms-tnef;\n" +
                            "\tname=\"winmail.dat\"\n" +
                            "Content-Transfer-Encoding: binary\n" +
                            "Content-class: " + contentClass + "\n" +
                            "Subject: " + MimeUtility.encodeText(subject) + "\n" +
                            "Date: " + date + "\n" +
                            "Message-ID: " + messageId + "\n" +
                            "Thread-Topic: " + MimeUtility.encodeText(threadTopic) + "\n" +
                            "Thread-Index: " + threadIndex + "\n" +
                            "From: " + from + "\n" +
                            "To: " + to + "\n";
                    if (priority != null) {
                        fullHeaders += "X-Priority: " + priority + "\n";
                    }
                } catch (ParseException e) {
                    throw new RuntimeException("Unable to rebuild header " + e.getMessage());
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException("Unable to rebuild header " + e.getMessage());
                }
            }
            StringBuffer result = new StringBuffer();
            boolean mstnefDetected = false;
            String boundary = null;
            try {
                BufferedReader reader = new BufferedReader(new StringReader(fullHeaders));
                String line;
                line = reader.readLine();
                while (line != null && line.length() > 0) {
                    // patch exchange Content type
                    if (line.equals(CONTENT_TYPE_HEADER + "application/ms-tnef;")) {
                        if (hasAttachment) {
                            boundary = "----_=_NextPart_001_" + uid;
                            String contentType = "multipart/mixed";
                            // use multipart/related with inline images
                            if (htmlBody != null && htmlBody.indexOf("src=\"cid:") > 0) {
                                contentType = "multipart/related";
                            }
                            line = CONTENT_TYPE_HEADER + contentType + ";\n\tboundary=\"" + boundary + "\"";
                        } else {
                            line = CONTENT_TYPE_HEADER + "text/html";
                        }
                        // skip winmail.dat
                        reader.readLine();
                        mstnefDetected = true;

                    } else if (line.startsWith(CONTENT_TRANSFER_ENCODING_HEADER)) {
                        if (hasAttachment && mstnefDetected) {
                            line = null;
                        }
                    }

                    if (line != null) {
                        result.append(line);
                        result.append("\n");
                    }
                    line = reader.readLine();
                }

                // exchange message : create mime part headers
                if (boundary != null) {
                    attachmentsMap = getAttachmentsUrls(messageUrl);
                    // TODO : test actual header values
                    result.append("\n--").append(boundary)
                            .append("\nContent-Type: text/html")
                            .append("\nContent-Transfer-Encoding: 7bit")
                            .append("\n\n");

                    for (String attachmentName : attachmentsMap.keySet()) {
                        // ignore indexed attachments
                        int parsedAttachmentIndex = 0;
                        try {
                            parsedAttachmentIndex = Integer.parseInt(attachmentName);
                        } catch (Exception e) {/* ignore */}
                        if (parsedAttachmentIndex == 0) {
                            String attachmentContentType = getAttachmentContentType(attachmentsMap.get(attachmentName));
                            String attachmentContentEncoding = "base64";
                            if (attachmentContentType.startsWith("text/")) {
                                attachmentContentEncoding = "quoted-printable";
                            } else if (attachmentContentType.startsWith("message/rfc822")) {
                                attachmentContentEncoding = "7bit";
                            }

                            result.append("\n--").append(boundary)
                                    .append("\nContent-Type: ")
                                    .append(attachmentContentType)
                                    .append(";")
                                    .append("\n\tname=\"").append(attachmentName).append("\"");
                            int attachmentIdStartIndex = htmlBody.indexOf("cid:" + attachmentName);
                            if (attachmentIdStartIndex > 0) {
                                int attachmentIdEndIndex = htmlBody.indexOf('"', attachmentIdStartIndex);
                                if (attachmentIdEndIndex > 0) {
                                    result.append("\nContent-ID: <")
                                            .append(htmlBody.substring(attachmentIdStartIndex + 4, attachmentIdEndIndex))
                                            .append(">");
                                }
                            }
                            result.append("\nContent-Transfer-Encoding: ").append(attachmentContentEncoding)
                                    .append("\n\n");
                        }
                    }

                    // end parts
                    result.append("--").append(boundary).append("--\n");
                }
                if (mstnefDetected) {
                    fullHeaders = result.toString();
                }

            } catch (IOException e) {
                throw new RuntimeException("Unable to preprocess headers " + e.getMessage());
            }
        }


        public void write(OutputStream os) throws IOException {
            // TODO : filter submessage headers in fullHeaders
            BufferedReader reader = new BufferedReader(new StringReader(fullHeaders));
            // skip first line
            reader.readLine();
            MimeHeader mimeHeader = new MimeHeader();
            mimeHeader.processHeaders(reader, os);
            // non MIME message without attachments, append body
            if (mimeHeader.boundary == null) {
                os.write('\r');
                os.write('\n');
                writeBody(os, mimeHeader);

                if (hasAttachment) {
                    os.write("**warning : missing attachments**".getBytes());
                }
            } else {
                attachmentIndex = 0;

                attachmentsMap = getAttachmentsUrls(messageUrl);
                writeMimeMessage(reader, os, mimeHeader, attachmentsMap);
            }
            os.flush();
        }

        public void writeMimeMessage(BufferedReader reader, OutputStream os, MimeHeader mimeHeader, Map<String, String> attachmentsMap) throws IOException {
            String line;
            // with alternative, there are two body forms (plain+html)
            if ("multipart/alternative".equals(mimeHeader.contentType)) {
                attachmentIndex--;
            }

            while (((line = reader.readLine()) != null) && !line.equals(mimeHeader.boundary + "--")) {
                os.write(line.getBytes());
                os.write('\r');
                os.write('\n');

                // detect part boundary start
                if (line.equals(mimeHeader.boundary)) {
                    // process current part header
                    MimeHeader partHeader = new MimeHeader();
                    partHeader.processHeaders(reader, os);

                    // detect inner mime message
                    if (partHeader.contentType != null
                            && partHeader.contentType.startsWith("multipart")
                            && partHeader.boundary != null) {
                        writeMimeMessage(reader, os, partHeader, attachmentsMap);
                    }
                    // body part
                    else if (attachmentIndex <= 0) {
                        attachmentIndex++;
                        writeBody(os, partHeader);
                    } else {
                        String attachmentUrl = attachmentsMap.get(partHeader.name);
                        // try to get attachment by index, only if no name found
                        if (attachmentUrl == null && partHeader.name == null) {
                            attachmentUrl = attachmentsMap.get(String.valueOf(attachmentIndex));
                        }
                        if (attachmentUrl == null) {
                            // only warn, could happen depending on IIS config
                            //throw new IOException("Attachment " + partHeader.name + " not found in " + messageUrl);
                            logger.warn("Attachment " + partHeader.name + " not found in " + messageUrl);
                        } else {
                            attachmentIndex++;
                            writeAttachment(os, partHeader, attachmentUrl);
                        }
                    }
                }
            }
            // write mime end marker
            if (line != null) {
                os.write(line.getBytes());
                os.write('\r');
                os.write('\n');
            }
        }

        protected void writeAttachment(OutputStream os, MimeHeader mimeHeader, String attachmentUrl) throws IOException {
            try {
                OutputStream quotedOs;
                try {
                    quotedOs = (MimeUtility.encode(os, mimeHeader.contentTransferEncoding));
                } catch (MessagingException e) {
                    throw new IOException(e.getMessage());
                }
                String decodedPath = URIUtil.decode(attachmentUrl);

                if ("message/rfc822".equals(mimeHeader.contentType)) {
                    // messages are not available at the attachment URL, but
                    // directly under the main message
                    String messageAttachmentPath = decodedPath;
                    int index = decodedPath.toLowerCase().lastIndexOf(".eml");
                    if (index > 0) {
                        messageAttachmentPath = decodedPath.substring(0, index + 4);
                    }

                    Message attachedMessage = getMessage(messageAttachmentPath);
                    attachedMessage.write(quotedOs);
                } else {

                    GetMethod method = new GetMethod(URIUtil.encodePathQuery(decodedPath));
                    wdr.retrieveSessionInstance().executeMethod(method);

                    // encode attachment
                    BufferedInputStream bis = new BufferedInputStream(method.getResponseBodyAsStream());
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = bis.read(buffer)) >= 0) {
                        quotedOs.write(buffer, 0, count);
                    }
                    bis.close();
                    quotedOs.flush();
                    os.write('\r');
                    os.write('\n');
                }

            } catch (HttpException e) {
                throw new IOException(e.getMessage());
            }
        }

        protected void writeBody(OutputStream os, MimeHeader mimeHeader) throws IOException {
            OutputStream quotedOs;
            try {
                quotedOs = (MimeUtility.encode(os, mimeHeader.contentTransferEncoding));
            } catch (MessagingException e) {
                throw new IOException(e.getMessage());
            }
            String currentBody;
            if ("text/html".equals(mimeHeader.contentType)) {
                currentBody = htmlBody;
                // patch charset if null and html body encoded
                if (mimeHeader.charset == null) {
                    String delimiter = "<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=";
                    int delimiterIndex = htmlBody.indexOf(delimiter);
                    if (delimiterIndex < 0) {
                        delimiter = "<meta http-equiv=Content-Type content=\"text/html; charset=";
                        delimiterIndex = htmlBody.indexOf(delimiter);
                    }
                    if (delimiterIndex < 0) {
                        delimiter = "<META http-equiv=Content-Type content=\"text/html; charset=";
                        delimiterIndex = htmlBody.indexOf(delimiter);
                    }

                    if (delimiterIndex > 0) {
                        int startIndex = delimiterIndex + delimiter.length();
                        int endIndex = htmlBody.indexOf('"', startIndex);
                        if (endIndex > 0) {
                            mimeHeader.charset = htmlBody.substring(startIndex, endIndex);
                        }
                    }
                }

            } else {
                currentBody = body;
            }
            if (mimeHeader.charset != null) {
                try {
                    quotedOs.write(currentBody.getBytes(MimeUtility.javaCharset(mimeHeader.charset)));
                } catch (UnsupportedEncodingException uee) {
                    // TODO : try to decode other encodings
                    quotedOs.write(currentBody.getBytes());
                }
            } else {
                quotedOs.write(currentBody.getBytes());
            }
            quotedOs.flush();
            os.write('\r');
            os.write('\n');
        }

        public void delete() throws IOException {
            // TODO : refactor
            String destination = deleteditemsUrl + messageUrl.substring(messageUrl.lastIndexOf("/"));
            logger.debug("Deleting : " + messageUrl + " to " + destination);
/*
// first try without webdav library
            GetMethod moveMethod = new GetMethod(URIUtil.encodePathQuery(messageUrl)) {
                public String getName() {
                    return "MOVE";
                }
            };
            moveMethod.addRequestHeader("Destination", URIUtil.encodePathQuery(destination));
            moveMethod.addRequestHeader("Overwrite", "F");
            wdr.retrieveSessionInstance().executeMethod(moveMethod);
            if (moveMethod.getStatusCode() == 412) {
                int count = 2;
                // name conflict, try another name
                while (wdr.getStatusCode() == 412) {
                    moveMethod = new GetMethod(URIUtil.encodePathQuery(messageUrl)) {
                        public String getName() {
                            return "MOVE";
                        }
                    };
                    moveMethod.addRequestHeader("Destination", URIUtil.encodePathQuery(destination.substring(0, destination.lastIndexOf('.')) + "-" + count++ + ".eml"));
                    moveMethod.addRequestHeader("Overwrite", "F");
                }

            }
            */
            wdr.moveMethod(messageUrl, destination);
            if (wdr.getStatusCode() == 412) {
                int count = 2;
                // name conflict, try another name
                while (wdr.getStatusCode() == 412) {
                    wdr.moveMethod(messageUrl, destination.substring(0, destination.lastIndexOf('.')) + "-" + count++ + ".eml");
                }
            }

            logger.debug("Deleted to :" + destination + " " + wdr.getStatusCode() + " " + wdr.getStatusMessage());

        }

        public void printHeaders(OutputStream os) throws IOException {
            String line;
            BufferedReader reader = new BufferedReader(new StringReader(fullHeaders));
            // skip first line
            reader.readLine();
            line = reader.readLine();
            while (line != null && line.length() > 0) {
                os.write(line.getBytes());
                os.write('\r');
                os.write('\n');
                line = reader.readLine();
            }
        }

        /**
         * Custom head method to force connection close (needed when attachment filtered by IIS)
         */
        protected class ConnectionCloseHeadMethod extends HeadMethod {
            public ConnectionCloseHeadMethod(String url) {
                super(url);
            }

            public boolean isConnectionCloseForced() {
                // force connection if attachment not found
                return getStatusCode() == 404;
            }

        }

        protected String getAttachmentContentType(String attachmentUrl) {
            String result;
            try {
                String decodedPath = URIUtil.decode(attachmentUrl);
                logger.debug("Head " + decodedPath);

                ConnectionCloseHeadMethod method = new ConnectionCloseHeadMethod(URIUtil.encodePathQuery(decodedPath));
                wdr.retrieveSessionInstance().executeMethod(method);
                if (method.getStatusCode() == 404) {
                    method.releaseConnection();
                    System.err.println("Unable to retrieve attachment");
                }
                result = method.getResponseHeader("Content-Type").getValue();
                method.releaseConnection();

            } catch (Exception e) {
                throw new RuntimeException("Exception retrieving " + attachmentUrl + " : " + e + " " + e.getCause());
            }
            return result;

        }

        public Map<String, String> getAttachmentsUrls(String messageUrl) throws IOException {
            if (attachmentsMap != null) {
                // do not load attachments twice
                return attachmentsMap;
            } else {

                GetMethod getMethod = new GetMethod(URIUtil.encodePathQuery(messageUrl + "?Cmd=Open"));
                wdr.retrieveSessionInstance().executeMethod(getMethod);
                if (getMethod.getStatusCode() != 200) {
                    throw new IOException("Unable to get attachments: " + getMethod.getStatusCode()
                            + " " + getMethod.getStatusLine());
                }

                InputStream in = getMethod.getResponseBodyAsStream();

                Tidy tidy = new Tidy();
                tidy.setXmlTags(false); //treat input not XML
                tidy.setQuiet(true);
                tidy.setShowWarnings(false);
                tidy.setDocType("omit");

                DOMBuilder builder = new DOMBuilder();
                XmlDocument xmlDocument = new XmlDocument();
                try {
                    xmlDocument.load(builder.build(tidy.parseDOM(in, null)));
                } catch (IOException ex1) {
                    ex1.printStackTrace();
                } catch (JDOMException ex1) {
                    ex1.printStackTrace();
                }
                // Release the connection.
                getMethod.releaseConnection();

                Map<String, String> attachmentsMap = new HashMap<String, String>();
                int attachmentIndex = 2;
                List<Attribute> list = xmlDocument.getNodes("//table[@id='idAttachmentWell']//a/@href");
                for (Attribute element : list) {
                    String attachmentHref = element.getValue();
                    if (!"#".equals(attachmentHref)) {
                        final String ATTACH_QUERY = "?attach=1";
                        if (attachmentHref.endsWith(ATTACH_QUERY)) {
                            attachmentHref = attachmentHref.substring(0, attachmentHref.length() - ATTACH_QUERY.length());
                        }
                        // url is encoded
                        attachmentHref = URIUtil.decode(attachmentHref);
                        if (attachmentHref.startsWith(messageUrl)) {
                            String attachmentName = attachmentHref.substring(messageUrl.length() + 1);
                            int slashIndex = attachmentName.indexOf('/');
                            if (slashIndex >= 0) {
                                attachmentName = attachmentName.substring(0, slashIndex);
                            }
                            // attachmentName is now right for Exchange message, need to handle external MIME messages
                            final String MULTIPART_STRING = "1_multipart_xF8FF_";
                            if (attachmentName.startsWith(MULTIPART_STRING)) {
                                attachmentName = attachmentName.substring(MULTIPART_STRING.length());
                                int underscoreIndex = attachmentName.indexOf('_');
                                if (underscoreIndex >= 0) {
                                    attachmentName = attachmentName.substring(underscoreIndex + 1);
                                }
                            }
                            // decode slashes
                            attachmentName = attachmentName.replaceAll("_xF8FF_", "/");

                            attachmentsMap.put(attachmentName, attachmentHref);
                            logger.debug("Attachment " + attachmentIndex + " : " + attachmentName);
                            attachmentsMap.put(String.valueOf(attachmentIndex++), attachmentHref);
                        } else {
                            logger.warn("Message URL : " + messageUrl + " is not a substring of attachment URL : " + attachmentHref);
                        }
                    }
                }

                // get inline images
                List<Attribute> imgList = xmlDocument.getNodes("//img/@src");
                for (Attribute element : imgList) {
                    String attachmentHref = element.getValue();
                    if (attachmentHref.startsWith("1_multipart")) {
                        attachmentHref =  URIUtil.decode(attachmentHref);
                        if (attachmentHref.endsWith("?Security=3")) {
                            attachmentHref = attachmentHref.substring(0, attachmentHref.indexOf('?'));
                        }
                        String attachmentName = attachmentHref.substring(attachmentHref.lastIndexOf('/') + 1);
                        if (attachmentName.charAt(1) == '_') {
                            attachmentName = attachmentName.substring(2);
                        }
                        // exclude inline external images
                        if (!attachmentHref.startsWith("http://") && !attachmentHref.startsWith("https://")) {
                            attachmentsMap.put(attachmentName, messageUrl + "/" + attachmentHref);
                            logger.debug("Inline image attachment " + attachmentIndex + " : " + attachmentName);
                            attachmentsMap.put(String.valueOf(attachmentIndex++), attachmentHref);
                        }
                    }
                }


                return attachmentsMap;
            }
        }

    }

    class MimeHeader {

        public String contentType = null;
        public String charset = null;
        public String contentTransferEncoding = null;
        public String boundary = null;
        public String name = null;

        public void processHeaders(BufferedReader reader, OutputStream os) throws IOException {
            String line;
            line = reader.readLine();
            while (line != null && line.length() > 0) {

                os.write(line.getBytes());
                os.write('\r');
                os.write('\n');

                String lineToCompare = line.toLowerCase();

                if (lineToCompare.startsWith(CONTENT_TYPE_HEADER)) {
                    String contentTypeHeader = line.substring(CONTENT_TYPE_HEADER.length());
                    // handle multi-line header
                    StringBuffer header = new StringBuffer(contentTypeHeader);
                    while (line.trim().endsWith(";")) {
                        line = reader.readLine();
                        header.append(line);

                        os.write(line.getBytes());
                        os.write('\r');
                        os.write('\n');
                    }
                    // decode header with accented file name (URL encoded)
                    int encodedIndex = header.indexOf("*=");
                    if (encodedIndex >= 0) {
                        StringBuffer decodedBuffer = new StringBuffer();
                        decodedBuffer.append(header.substring(0, encodedIndex));
                        decodedBuffer.append('=');
                        int encodedDataIndex = header.indexOf("''");
                        if (encodedDataIndex >= 0) {
                            String encodedData = header.substring(encodedDataIndex + 2);
                            String encodedDataCharset = header.substring(encodedIndex + 2, encodedDataIndex);
                            decodedBuffer.append(URIUtil.decode(encodedData, encodedDataCharset));
                            header = decodedBuffer;
                        }
                    }
                    StringTokenizer tokenizer = new StringTokenizer(header.toString(), ";");
                    // first part is Content type
                    if (tokenizer.hasMoreTokens()) {
                        contentType = tokenizer.nextToken().trim();
                    }
                    while (tokenizer.hasMoreTokens()) {
                        String token = tokenizer.nextToken().trim();
                        int equalsIndex = token.indexOf('=');
                        if (equalsIndex > 0) {
                            String tokenName = token.substring(0, equalsIndex);
                            if ("charset".equals(tokenName)) {
                                charset = token.substring(equalsIndex + 1);
                                if (charset.startsWith("\"")) {
                                    charset = charset.substring(1, charset.lastIndexOf("\""));
                                }
                            } else if ("name".equals(tokenName.toLowerCase())) {
                                name = token.substring(equalsIndex + 1);
                                if (name.startsWith("\"")) {
                                    name = name.substring(1, name.lastIndexOf("\""));
                                }
                            } else if ("boundary".equals(tokenName)) {
                                boundary = token.substring(equalsIndex + 1);
                                if (boundary.startsWith("\"")) {
                                    boundary = boundary.substring(1, boundary.lastIndexOf("\""));
                                }
                                boundary = "--" + boundary;
                            }
                        }
                    }
                } else if (lineToCompare.startsWith(CONTENT_TRANSFER_ENCODING_HEADER)) {
                    contentTransferEncoding = line.substring(CONTENT_TRANSFER_ENCODING_HEADER.length()).trim();
                }
                line = reader.readLine();
            }
            os.write('\r');
            os.write('\n');
        }
    }
}
