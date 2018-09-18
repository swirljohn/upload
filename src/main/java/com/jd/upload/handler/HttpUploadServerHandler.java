
package com.jd.upload.handler;

import com.jd.upload.constants.UploadConstants;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.multipart.*;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import io.netty.handler.codec.http.multipart.InterfaceHttpData.HttpDataType;
import io.netty.util.CharsetUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Set;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.handler.codec.http.HttpHeaders.Names.*;

public class HttpUploadServerHandler extends SimpleChannelInboundHandler<HttpObject> {

    private static final Log logger = LogFactory.getLog(HttpUploadServerHandler.class);

    private HttpRequest request;

    private boolean readingChunks;

    private final StringBuilder responseContent = new StringBuilder();

    private static final HttpDataFactory factory =
            new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);

    private HttpPostRequestDecoder decoder;

    static {
        DiskFileUpload.deleteOnExitTemporaryFile = true;
        DiskFileUpload.baseDirectory = null;
        DiskAttribute.deleteOnExitTemporaryFile = true;
        DiskAttribute.baseDirectory = null;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
        logger.info("channelRegistered");
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        if (decoder != null) {
            decoder.cleanFiles();
        }

        logger.info("channelUnregistered");
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, HttpObject httpObject) throws Exception {

        if (httpObject instanceof HttpRequest) {

            HttpRequest httpRequest = (HttpRequest) httpObject;
            doHttpRequest(ctx, httpRequest);
        } else if (httpObject instanceof HttpContent) {

            HttpContent httpContent = (HttpContent) httpObject;
            doHttpContent(ctx, httpContent);
        }
        else {
            logger.error("Unknown http object");
        }

    }

    private void doGet(ChannelHandlerContext ctx, HttpRequest httpRequest) {

        responseContent.setLength(0);
        responseContent.append(httpRequest.getMethod().name()).append(" method not support!");

        writeResponse(ctx.channel());

    }

    private void doPost(ChannelHandlerContext ctx, HttpRequest httpRequest) throws URISyntaxException {

        try {
            decoder = new HttpPostRequestDecoder(factory, httpRequest);
        } catch (ErrorDataDecoderException e) {
            e.printStackTrace();
            responseContent.append(e.getMessage());
            writeResponse(ctx.channel());
            ctx.channel().close();
        }

    }

    private void doHttpRequest(ChannelHandlerContext ctx, HttpRequest httpRequest) throws URISyntaxException {
        HttpRequest request = this.request = httpRequest;
        HttpMethod httpMethod = request.getMethod();

        if (httpMethod.equals(HttpMethod.GET)) {
            doGet(ctx, request);

        } else if (httpMethod.equals(HttpMethod.POST)) {
            doPost(ctx, request);

        } else {

            responseContent.setLength(0);
            responseContent.append(httpMethod.name()).append(" method not support!");
            writeResponse(ctx.channel());
            logger.error(responseContent.toString());
        }
    }

    private void doHttpContent(ChannelHandlerContext ctx, HttpContent httpContent) throws URISyntaxException {

        URI uri = new URI(request.getUri());
        if (uri.getPath().startsWith("/upload")) {

            if (decoder != null) {
                // New chunk is received
                try {
                    decoder.offer(httpContent);
                } catch (ErrorDataDecoderException e1) {
                    e1.printStackTrace();
                    responseContent.append(e1.getMessage());
                    writeResponse(ctx.channel());
                    ctx.channel().close();
                    return;
                }
                responseContent.append('o');
                readHttpDataChunkByChunk();
                logger.info("readHttpDataChunkByChunk");
                if (httpContent instanceof LastHttpContent) {
                    responseContent.setLength(0);
                    responseContent.append("200 ok");
                    writeResponse(ctx.channel());
                    readingChunks = false;
                    reset();

                }
            } else {
                writeResponse(ctx.channel());
            }
        } else {
            logger.error("Unknown Path: " + uri.getPath());
            responseContent.append("Unknown Path: ").append(uri.getPath());
            writeResponse(ctx.channel());
        }

    }

    private void reset() {
        request = null;

        // destroy the decoder to release all resources
        decoder.destroy();
        decoder = null;
    }

    /**
     * Example of reading request by chunk and getting values from chunk to chunk
     */
    private void readHttpDataChunkByChunk() throws EndOfDataDecoderException {
        while (decoder.hasNext()) {
            InterfaceHttpData data = decoder.next();
            try {
                writeHttpData(data);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                data.release();
            }
        }
    }

    private void writeHttpData(InterfaceHttpData data) throws IOException {
        logger.info("HTTP DATA name - " + data.getHttpDataType().name());
        if (data.getHttpDataType() == HttpDataType.Attribute) {
            Attribute attribute = (Attribute) data;
            String name = attribute.getName();
            String value = attribute.getValue();
            logger.info("name - " + name + ", value - " + value);

        } else if (data.getHttpDataType() == HttpDataType.FileUpload) {
            FileUpload fileUpload = (FileUpload) data;
            if (fileUpload.isCompleted()) {
                logger.info("data - " + data);
                logger.info("File name: " + fileUpload.getFilename() + ", length - " + fileUpload.length());
                logger.info("File isInMemory - " + fileUpload.isInMemory());
                logger.info("File rename to ...");
//                File dest = new File(UploadConstants.FILE_DIR, fileUpload.getFile().getName());
                File dest = new File(UploadConstants.FILE_DIR, fileUpload.getFilename());
                fileUpload.renameTo(dest);
                decoder.removeHttpDataFromClean(fileUpload);
                logger.info("File rename over .");
            } else {
                logger.debug("File to be continued!");
            }

        }
    }

    private void writeResponse(Channel channel) {
        logger.info("writeResponse ...");
        // Convert the response content to a ChannelBuffer.
        ByteBuf buf = copiedBuffer(responseContent.toString(), CharsetUtil.UTF_8);
        responseContent.setLength(0);

        // Decide whether to close the connection or not.
        boolean close = HttpHeaders.Values.CLOSE.equalsIgnoreCase(request.headers().get(CONNECTION))
                || request.getProtocolVersion().equals(HttpVersion.HTTP_1_0)
                && !HttpHeaders.Values.KEEP_ALIVE.equalsIgnoreCase(request.headers().get(CONNECTION));

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
        response.headers().set(CONTENT_TYPE, "text/plain; charset=UTF-8");

        if (!close) {
            response.headers().set(CONTENT_LENGTH, buf.readableBytes());
        }

        Set<Cookie> cookies;
        String value = request.headers().get(COOKIE);
        if (value == null) {
            cookies = Collections.emptySet();
        } else {
            cookies = ServerCookieDecoder.STRICT.decode(value);
        }
        if (!cookies.isEmpty()) {
            for (Cookie cookie : cookies) {
                response.headers().add(HttpHeaders.Names.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
            }
        }
        ChannelFuture future = channel.writeAndFlush(response);
        if (close) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.info(responseContent.toString(), cause);
        ctx.channel().close();
    }

    static String error(int code, String desc) {
        return code + ": " + desc;
    }
}
