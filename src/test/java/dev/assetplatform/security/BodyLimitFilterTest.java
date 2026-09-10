package dev.assetplatform.security;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.assetplatform.exception.ErrorWriter;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class BodyLimitFilterTest {
  private final BodyLimitFilter filter =
      new BodyLimitFilter(new ErrorWriter(JsonMapper.builder().findAndAddModules().build()));

  @Test
  void encodedAuthPrefixCannotBypassBodyLimit() throws Exception {
    var req = new MockHttpServletRequest("POST", "/api/v1/%61uth/register");
    req.setServletPath("/api/v1/auth/register");
    req.setContent(new byte[16385]);
    var res = new MockHttpServletResponse();
    filter.doFilter(req, res, new MockFilterChain());
    assertThat(res.getStatus()).isEqualTo(413);
  }

  @Test
  void contextPathCannotBypassKnownLengthLimit() throws Exception {
    var request = new MockHttpServletRequest("POST", "/platform/api/v1/auth/register");
    request.setContextPath("/platform");
    request.setContent(new byte[16385]);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void boundsUnknownLengthStreamsAndAllowsExactLimit() throws Exception {
    for (int size : new int[] {16384, 16385}) {
      var request =
          new MockHttpServletRequest("POST", "/api/v1/auth/login") {
            @Override
            public long getContentLengthLong() {
              return -1;
            }
          };
      request.setContent(new byte[size]);
      AtomicInteger read = new AtomicInteger();
      var action =
          (org.assertj.core.api.ThrowableAssert.ThrowingCallable)
              () ->
                  filter.doFilter(
                      request,
                      new MockHttpServletResponse(),
                      (req, res) -> read.set(req.getInputStream().readAllBytes().length));
      if (size == 16384) {
        assertThatCode(action).doesNotThrowAnyException();
        assertThat(read.get()).isEqualTo(size);
      } else assertThatThrownBy(action).isInstanceOf(BodyLimitFilter.BodyTooLargeException.class);
    }
  }
}
