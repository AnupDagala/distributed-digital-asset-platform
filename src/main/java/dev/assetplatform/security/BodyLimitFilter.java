package dev.assetplatform.security;

import dev.assetplatform.exception.ErrorWriter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class BodyLimitFilter extends OncePerRequestFilter {
  public static final class BodyTooLargeException extends IOException {}

  private static final int JSON_LIMIT = 16384;
  private final ErrorWriter errors;

  public BodyLimitFilter(ErrorWriter errors) {
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = RequestPaths.applicationPath(request);
    if (!path.startsWith("/api/v1/auth/")) {
      chain.doFilter(request, response);
      return;
    }
    if (request.getContentLengthLong() > JSON_LIMIT) {
      errors.write(
          request,
          response,
          HttpStatus.PAYLOAD_TOO_LARGE,
          "BODY_TOO_LARGE",
          "JSON body exceeds 16 KiB");
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          private ServletInputStream bounded;

          @Override
          public ServletInputStream getInputStream() throws IOException {
            if (bounded != null) return bounded;
            ServletInputStream source = super.getInputStream();
            bounded =
                new ServletInputStream() {
                  private long count;

                  private void add(int n) throws IOException {
                    if (n > 0 && ((count += n) > JSON_LIMIT)) throw new BodyTooLargeException();
                  }

                  @Override
                  public int read() throws IOException {
                    int value = source.read();
                    add(value < 0 ? 0 : 1);
                    return value;
                  }

                  @Override
                  public int read(byte[] b, int offset, int length) throws IOException {
                    int n = source.read(b, offset, length);
                    add(n);
                    return n;
                  }

                  @Override
                  public boolean isFinished() {
                    return source.isFinished();
                  }

                  @Override
                  public boolean isReady() {
                    return source.isReady();
                  }

                  @Override
                  public void setReadListener(ReadListener listener) {
                    source.setReadListener(listener);
                  }
                };
            return bounded;
          }
        },
        response);
  }
}
