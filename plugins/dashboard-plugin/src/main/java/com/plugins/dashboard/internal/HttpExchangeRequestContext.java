package internal;

import com.sun.net.httpserver.HttpExchange;
import org.apache.commons.fileupload.UploadContext;

import java.io.IOException;
import java.io.InputStream;

/**
 * Wrapper für HttpExchange, um mit Apache Commons FileUpload kompatibel zu sein.
 * Implementiert UploadContext für Support großer Dateien (>2GB) und Vermeidung von Deprecation-Warnungen.
 */
public class HttpExchangeRequestContext implements UploadContext {
    private final HttpExchange exchange;

    public HttpExchangeRequestContext(HttpExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public String getCharacterEncoding() {
        return "UTF-8";
    }

    @Override
    public String getContentType() {
        return exchange.getRequestHeaders().getFirst("Content-Type");
    }

    @Override
    public int getContentLength() {
        return (int) contentLength();
    }

    @Override
    public long contentLength() {
        String len = exchange.getRequestHeaders().getFirst("Content-Length");
        try {
            return len != null ? Long.parseLong(len) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return exchange.getRequestBody();
    }
}