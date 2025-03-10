/*
 * This file is part of Universal Media Server, based on PS3 Media Server.
 *
 * This program is a free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; version 2 of the License only.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package net.pms.platform.windows;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import java.io.*;
import java.util.ArrayList;
import net.pms.io.BufferedOutputFile;
import net.pms.io.BufferedOutputFileImpl;
import net.pms.io.OutputParams;
import net.pms.io.ProcessWrapper;
import net.pms.util.UMSUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WindowsNamedPipe extends Thread implements ProcessWrapper {
	private static final Logger LOGGER = LoggerFactory.getLogger(WindowsNamedPipe.class);
	private String path;
	private boolean in;
	private boolean forceReconnect;
	private HANDLE handle1;
	private HANDLE handle2;
	private OutputStream writable;
	private InputStream readable;
	private Thread forced;
	private boolean b2;
	private FileOutputStream debug;
	private BufferedOutputFile directBuffer;
	private static boolean loop = true;

	/**
	 * Size for the buffer used in defining pipes for Windows in bytes. The buffer is used
	 * to copy from memory to an {@link java.io.OutputStream OutputStream} such as
	 * {@link net.pms.io.BufferedOutputFile BufferedOutputFile}.
	 */
	private static final int BUFSIZE = 500000;

	public String getPipeName() {
		return path;
	}

	public OutputStream getWritable() {
		return writable;
	}

	public InputStream getReadable() {
		return readable;
	}

	public BufferedOutputFile getDirectBuffer() {
		return directBuffer;
	}

	@Override
	public InputStream getInputStream(long seek) throws IOException {
		return null;
	}

	@Override
	public ArrayList<String> getResults() {
		return null;
	}

	@Override
	public boolean isDestroyed() {
		return !isAlive();
	}

	@Override
	public void runInNewThread() {
		// Constructor already called start(), do nothing
	}

	@Override
	public void runInSameThread() {
		// Constructor already called start(), do nothing
	}

	@Override
	public boolean isReadyToStop() {
		return false;
	}

	@Override
	public void setReadyToStop(boolean nullable) { }

	@Override
	public void stopProcess() {
		interrupt();
	}

	/**
	 * Whether the code will loop.
	 *
	 * @param value whether the code will loop.
	 */
	// XXX this can be handled in a shutdown hook
	public static void setLoop(boolean value) {
		loop = value;
	}

	public WindowsNamedPipe(String basename, boolean forceReconnect, boolean in, OutputParams params) {
		this.path = "\\\\.\\pipe\\" + basename;
		this.in = in;
		this.forceReconnect = forceReconnect;
		LOGGER.debug("Creating pipe " + this.path);

		try {
			handle1 = Kernel32.INSTANCE.CreateNamedPipe(
				this.path,
				3,
				0,
				255,
				BUFSIZE,
				BUFSIZE,
				0,
				null
			);

			if (forceReconnect) {
				handle2 = Kernel32.INSTANCE.CreateNamedPipe(
					this.path,
					3,
					0,
					255,
					BUFSIZE,
					BUFSIZE,
					0,
					null
				);
			}

			if (params != null) {
				directBuffer = new BufferedOutputFileImpl(params);
			} else {
				writable = new PipedOutputStream();
				readable = new PipedInputStream((PipedOutputStream) writable, BUFSIZE);
			}

			start();

			if (forceReconnect) {
				forced = new Thread(() -> b2 = Kernel32.INSTANCE.ConnectNamedPipe(handle2, null), "Forced Reconnector");

				forced.start();
			}
		} catch (Exception e1) {
			LOGGER.warn("Error creating Windows named pipe: {}", e1.getMessage());
			LOGGER.trace("", e1);
		}
	}

	@Override
	public void run() {
		LOGGER.debug("Waiting for Windows named pipe connection \"{}\"", path);
		boolean b1 = Kernel32.INSTANCE.ConnectNamedPipe(handle1, null);

		if (forceReconnect) {
			while (forced.isAlive()) {
				UMSUtils.sleep(200);
			}

			LOGGER.debug("Forced reconnection of {} with result: {}", path, b2);
			handle1 = handle2;
		}

		LOGGER.debug("Result of {}: {}", path, b1);

		try {
			if (b1) {
				if (in) {
					IntByReference intRef = new IntByReference();
					byte[] buffer = new byte[BUFSIZE];

					while (loop) {
						boolean fSuccess = Kernel32.INSTANCE.ReadFile(
							handle1,
							buffer,
							BUFSIZE,
							intRef,
							null
						);

						int cbBytesRead = intRef.getValue();

						if (cbBytesRead == -1) {
							if (directBuffer != null) {
								directBuffer.close();
							}

							if (writable != null) {
								writable.close();
							}

							if (debug != null) {
								debug.close();
							}

							break;
						}

						if (directBuffer != null) {
							directBuffer.write(buffer, 0, cbBytesRead);
						}

						if (writable != null) {
							writable.write(buffer, 0, cbBytesRead);
						}

						if (debug != null) {
							debug.write(buffer, 0, cbBytesRead);
						}

						if (!fSuccess || cbBytesRead == 0) {
							if (directBuffer != null) {
								directBuffer.close();
							}

							if (writable != null) {
								writable.close();
							}

							if (debug != null) {
								debug.close();
							}

							break;
						}
					}
				} else {
					byte[] buffer = new byte[BUFSIZE];
					IntByReference intRef = new IntByReference();

					while (loop) {
						int cbBytesRead = readable.read(buffer);

						if (cbBytesRead == -1) {
							readable.close();

							if (debug != null) {
								debug.close();
							}

							break;
						}

						boolean fSuccess = Kernel32.INSTANCE.WriteFile(
							handle1,
							buffer,
							cbBytesRead,
							intRef,
							null
						);

						int cbWritten = intRef.getValue();

						if (debug != null) {
							debug.write(buffer, 0, cbBytesRead);
						}

						if (!fSuccess || cbWritten == 0) {
							readable.close();

							if (debug != null) {
								debug.close();
							}

							break;
						}
					}
				}
			}
		} catch (InterruptedIOException e) {
			if (LOGGER.isDebugEnabled()) {
				if (StringUtils.isNotBlank(e.getMessage())) {
					LOGGER.debug("Windows named pipe interrupted after writing {} bytes, shutting down: {}", e.bytesTransferred, e.getMessage());
				} else {
					LOGGER.debug("Windows named pipe interrupted after writing {} bytes, shutting down...", e.bytesTransferred);
				}
				LOGGER.trace("", e);
			}
		} catch (IOException e) {
			LOGGER.debug("Windows named pipe error: {}", e.getMessage());
			LOGGER.trace("", e);
		}

		if (!in) {
			LOGGER.debug("Disconnecting Windows named pipe: {}", path);
			Kernel32.INSTANCE.FlushFileBuffers(handle1);
			Kernel32.INSTANCE.DisconnectNamedPipe(handle1);
		} else {
			Kernel32.INSTANCE.CloseHandle(handle1);
		}
	}
}
