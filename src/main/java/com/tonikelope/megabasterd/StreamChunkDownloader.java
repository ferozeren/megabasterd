package com.tonikelope.megabasterd;

import static com.tonikelope.megabasterd.MainPanel.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author tonikelope
 */
public class StreamChunkDownloader implements Runnable {

    private final int _id;
    private final StreamChunkManager _chunkmanager;
    private volatile boolean _exit;
    private SmartMegaProxyManager _proxy_manager;

    public StreamChunkDownloader(int id, StreamChunkManager chunkmanager) {
        _id = id;
        _chunkmanager = chunkmanager;
        _proxy_manager = null;
        _exit = false;
    }

    public void setExit(boolean exit) {
        _exit = exit;
    }

    @Override
    public void run() {

        Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker [{1}]: let''s do some work!", new Object[]{Thread.currentThread().getName(), _id});

        HttpURLConnection con = null;

        try {

            String url = _chunkmanager.getUrl();

            int http_error = 0;

            String current_smart_proxy = null;

            long offset = -1;

            while (!_exit && !_chunkmanager.isExit()) {

                if (MainPanel.isUse_smart_proxy() && _proxy_manager == null) {

                    _proxy_manager = new SmartMegaProxyManager(null);

                }

                while (!_exit && !_chunkmanager.isExit() && _chunkmanager.getChunk_queue().size() >= StreamChunkManager.BUFFER_CHUNKS_SIZE) {

                    Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker [{1}]: Chunk buffer is full. I pause myself.", new Object[]{Thread.currentThread().getName(), _id});

                    _chunkmanager.secureWait();
                }

                if (http_error == 0) {

                    offset = _chunkmanager.nextOffset();

                } else if (http_error == 403) {

                    url = _chunkmanager.getUrl();
                }

                if (offset >= 0) {

                    StreamChunk chunk_stream = new StreamChunk(offset, _chunkmanager.calculateChunkSize(offset), url);

                    if (http_error == 509 && MainPanel.isUse_smart_proxy() && !MainPanel.isUse_proxy()) {

                        if (current_smart_proxy != null) {

                            _proxy_manager.blockProxy(current_smart_proxy);
                        }

                        current_smart_proxy = _proxy_manager.getFastestProxy();

                        if (current_smart_proxy != null) {

                            String[] proxy_info = current_smart_proxy.split(":");

                            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy_info[0], Integer.parseInt(proxy_info[1])));

                            URL chunk_url = new URL(chunk_stream.getUrl());

                            con = (HttpURLConnection) chunk_url.openConnection(proxy);

                        } else {

                            URL chunk_url = new URL(chunk_stream.getUrl());

                            con = (HttpURLConnection) chunk_url.openConnection();
                        }

                    } else {

                        URL chunk_url = new URL(chunk_stream.getUrl());

                        if (MainPanel.isUse_proxy()) {

                            con = (HttpURLConnection) chunk_url.openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(MainPanel.getProxy_host(), MainPanel.getProxy_port())));

                            if (MainPanel.getProxy_user() != null && !"".equals(MainPanel.getProxy_user())) {

                                con.setRequestProperty("Proxy-Authorization", "Basic " + MiscTools.Bin2BASE64((MainPanel.getProxy_user() + ":" + MainPanel.getProxy_pass()).getBytes()));
                            }
                        } else {

                            con = (HttpURLConnection) chunk_url.openConnection();
                        }
                    }

                    con.setConnectTimeout(Transference.HTTP_TIMEOUT);

                    con.setReadTimeout(Transference.HTTP_TIMEOUT);

                    con.setRequestProperty("User-Agent", MainPanel.DEFAULT_USER_AGENT);

                    int reads, http_status;

                    byte[] buffer = new byte[DEFAULT_BYTE_BUFFER_SIZE];

                    Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker [{1}]: offset: {2} size: {3}", new Object[]{Thread.currentThread().getName(), _id, offset, chunk_stream.getSize()});

                    http_error = 0;

                    try {

                        if (!_exit) {

                            http_status = con.getResponseCode();

                            if (http_status != 200) {

                                Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Failed : HTTP error code : {1}", new Object[]{Thread.currentThread().getName(), http_status});

                                http_error = http_status;

                            } else {

                                try (InputStream is = con.getInputStream()) {

                                    int chunk_writes = 0;

                                    while (!_exit && !_chunkmanager.isExit() && chunk_writes < chunk_stream.getSize() && (reads = is.read(buffer, 0, Math.min((int) (chunk_stream.getSize() - chunk_writes), buffer.length))) != -1) {

                                        chunk_stream.getOutputStream().write(buffer, 0, reads);

                                        chunk_writes += reads;
                                    }

                                    if (chunk_stream.getSize() == chunk_writes) {

                                        Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker [{1}] has downloaded chunk [{2}]!", new Object[]{Thread.currentThread().getName(), _id, chunk_stream.getOffset()});

                                        _chunkmanager.getChunk_queue().put(chunk_stream.getOffset(), chunk_stream);

                                        _chunkmanager.secureNotifyAll();
                                    }
                                }
                            }
                        }

                    } catch (IOException ex) {
                        Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                    } finally {
                        con.disconnect();
                    }

                } else {

                    _exit = true;
                }
            }

        } catch (IOException | URISyntaxException | ChunkInvalidException | InterruptedException ex) {
            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
        } catch (OutOfMemoryError | Exception ex) {
            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
        }

        _chunkmanager.secureNotifyAll();

        Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker [{1}]: bye bye", new Object[]{Thread.currentThread().getName(), _id});
    }

}
