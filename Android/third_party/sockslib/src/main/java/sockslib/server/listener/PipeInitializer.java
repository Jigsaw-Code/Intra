package sockslib.server.listener;

import sockslib.server.io.Pipe;
import sockslib.server.io.PipeListener;
import sockslib.server.io.SocketPipe;

import java.net.Socket;
import java.util.List;

/**
 * The class <code></code>
 *
 * @author Youchao Feng
 * @version 1.0
 * @date Mar 03,2016 7:24 PM
 */
public interface PipeInitializer {

  Pipe initialize(Pipe pipe);

}
