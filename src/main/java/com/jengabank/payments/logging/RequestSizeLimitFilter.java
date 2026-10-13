package com.jengabank.payments.logging;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jengabank.payments.error.ApiError;

/**
 * Enforces the team's 16 KB max request size (jengabank.security.max-request-bytes) for
 * EVERY request, not just form posts. server.tomcat.max-http-form-post-size only caps
 * application/x-www-form-urlencoded parsing - a JSON body of any size sails straight
 * through it and gets fully buffered by Jackson, so that alone doesn't protect our API.
 * Content-Length is checked up front (cheap, rejects before reading anything); a body
 * sent chunked with no declared length is capped while reading instead, so omitting
 * Content-Length isn't a way around the limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // right after RequestLogFilter assigns the request id
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ObjectMapper mapper;

    public RequestSizeLimitFilter(@Value("${jengabank.security.max-request-bytes:16384}") long maxBytes,
                                   ObjectMapper mapper) {
        this.maxBytes = maxBytes;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            tooLarge(response);
            return;
        }
        chain.doFilter(new CappedRequest(request, maxBytes), response);
    }

    private void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(),
                new ApiError("PAYLOAD_TOO_LARGE", "Request body is too large.", MDC.get(RequestLogFilter.MDC_REQUEST_ID), List.of()));
    }

    private static final class CappedRequest extends HttpServletRequestWrapper {
        private final long maxBytes;

        CappedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new CappedStream(super.getInputStream(), maxBytes);
        }
    }

    /** Throws once more than maxBytes have been read, so an undeclared (chunked) body can't bypass the cap. */
    private static final class CappedStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read = 0;

        CappedStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (read >= maxBytes) {
                throw new IOException("request body exceeds " + maxBytes + " bytes");
            }
            int b = delegate.read();
            if (b != -1) {
                read++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (read >= maxBytes) {
                throw new IOException("request body exceeds " + maxBytes + " bytes");
            }
            int toRead = (int) Math.min(len, maxBytes - read);
            int n = delegate.read(b, off, toRead);
            if (n > 0) {
                read += n;
            }
            return n;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
