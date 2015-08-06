package org.jolokia.docker.maven.access.log;/*
 * 
 * Copyright 2014 Roland Huss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.common.io.ByteStreams;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.jolokia.docker.maven.access.DockerAccessException;
import org.jolokia.docker.maven.access.UrlBuilder;
import org.jolokia.docker.maven.util.Timestamp;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jolokia.docker.maven.access.util.RequestUtil.newGet;

/**
 * Extractor for parsing the response of a log request
 *
 * @author roland
 * @since 28/11/14
 */
public class LogRequestor extends Thread implements LogGetHandle {

    // Patter for matching log entries
    private static final Pattern LOG_LINE = Pattern.compile("^\\[?([^\\s\\]]*)]?\\s+(.*)\\s*$");
    private final HttpClient client;

    private final String containerId;

    // callback called for each line extracted
    private LogCallback callback;

    private DockerAccessException exception;

    // Remember for asynchronous handling
    private HttpUriRequest request;

    private final UrlBuilder urlBuilder;
    
    /**
     * Create a helper object for requesting log entries synchronously ({@link #fetchLogs()}) or asynchronously ({@link #start()}.
     *
     * @param client HTTP client to use for requesting the docker host
     * @param urlBuilder builder that creates docker urls
     * @param containerId container for which to fetch the host
     * @param callback callback to call for each line received
     */
    public LogRequestor(HttpClient client, UrlBuilder urlBuilder, String containerId, LogCallback callback) {
        this.client = client;
        this.containerId = containerId;
        
        this.urlBuilder = urlBuilder;

        this.callback = callback;
        this.exception = null;
        this.setDaemon(true);
    }

    /**
     * Get logs and feed a callback with the content
     */
    public void fetchLogs() {
        try {
            HttpResponse resp = client.execute(getLogRequest(false));
            parseResponse(resp);
        } catch (IOException exp) {
            callback.error(exp.getMessage());
        }
    }

    // Fetch log asynchronously as stream and follow stream
    public void run() {
        // Response to extract from

        try {
            request = getLogRequest(true);
            HttpResponse response = client.execute(request);
            parseResponse(response);
        } catch (IOException exp) {
            callback.error("IO Error while requesting logs: " + exp);
        }
    }

    private boolean readStreamFrame(InputStream is) throws IOException, LogCallback.DoneException {
        // Read the header, which is composed of eight bytes. The first byte is an integer
        // indicating the stream type (0 = stdin, 1 = stdout, 2 = stderr), the next three are thrown
        // out, and the final four are the size of the remaining stream as an integer.
        ByteBuffer headerBuffer = ByteBuffer.allocate(8);
        headerBuffer.order(ByteOrder.BIG_ENDIAN);
        try {
            ByteStreams.readFully(is, headerBuffer.array());
        } catch (EOFException e) {
            return false;
        }

        // Grab the stream type (stdout, stderr, stdin) from first byte and throw away other 3 bytes.
        int type = headerBuffer.get();
        // Skip three bytes
        headerBuffer.get();
        headerBuffer.get();
        headerBuffer.get();

        // Read size from remaining four bytes.
        int size = headerBuffer.getInt();

        // Read the actual message
        ByteBuffer payload = ByteBuffer.allocate(size);
        try {
            ByteStreams.readFully(is, payload.array());
        } catch (EOFException e) {
            return false;
        }

        callLogCallback(type, payload.toString());
        return true;
    }

    private void parseResponse(HttpResponse response) {
        try (InputStream is = response.getEntity().getContent()) {
            while (readStreamFrame(is)) {
                // empty
            }
            StatusLine status = response.getStatusLine();
            if (status.getStatusCode() != 200) {
                exception = new DockerAccessException("Error while reading logs (" + status + ")");
            }
        } catch (IOException e) {
            callback.error("Cannot process chunk response: " + e);
            finish();
        } catch (LogCallback.DoneException e) {
            // Can be thrown by a log callback which indicates that we are done.
            finish();
        }
    }

    private void callLogCallback(int type, String txt) throws LogCallback.DoneException {
        Matcher matcher = LOG_LINE.matcher(txt);
        if (!matcher.matches()) {
            callback.error("Invalid log format for '" + txt + "' (expected: \"<timestamp> <txt>\")");
            throw new LogCallback.DoneException();
        }
        Timestamp ts = new Timestamp(matcher.group(1));
        String logTxt = matcher.group(2);
        callback.log(type, ts, logTxt);
    }

    private int extractLength(byte[] b) {
        return b[7] & 0xFF |
               (b[6] & 0xFF) << 8 |
               (b[5] & 0xFF) << 16 |
               (b[4] & 0xFF) << 24;
    }


    private HttpUriRequest getLogRequest(boolean follow) {
        return newGet(urlBuilder.containerLogs(containerId, follow));
    }

    @Override
    public void finish() {
        if (request != null) {
            request.abort();
        }
    }

    @Override
    public boolean isError() {
        return exception != null;
    }

    @Override
    public DockerAccessException getException() {
        return exception;
    }
}
