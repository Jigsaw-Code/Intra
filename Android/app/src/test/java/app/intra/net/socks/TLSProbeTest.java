package app.intra.net.socks;

import static org.junit.Assert.*;

import app.intra.net.socks.TLSProbe.Result;
import org.junit.Test;

public class TLSProbeTest {

  @Test
  public void success() {
    assertEquals(Result.SUCCESS,
        TLSProbe.run(null, TLSProbe.DEFAULT_URL));
  }

  @Test
  public void tlsFailed() {
    assertEquals(Result.TLS_FAILED,
        TLSProbe.run(null, "https://untrusted-root.badssl.com/"));
  }

  @Test
  public void otherFailed() {
    assertEquals(Result.OTHER_FAILED,
        TLSProbe.run(null, "https://bad-domain.bad-tld/"));
  }
}
