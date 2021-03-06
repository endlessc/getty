/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gettyio.core.channel;

import com.gettyio.core.buffer.BufferWriter;
import com.gettyio.core.buffer.ChunkPool;
import com.gettyio.core.channel.config.BaseConfig;
import com.gettyio.core.channel.internal.ReadCompletionHandler;
import com.gettyio.core.channel.internal.WriteCompletionHandler;
import com.gettyio.core.function.Function;
import com.gettyio.core.handler.ssl.SslHandler;
import com.gettyio.core.handler.ssl.sslfacade.IHandshakeCompletedListener;
import com.gettyio.core.pipeline.ChannelPipeline;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * AioChannel.java
 *
 * @description: aio通道
 * @author:gogym
 * @date:2020/4/8
 * @copyright: Copyright by gettyio.com
 */
public class AioChannel extends SocketChannel implements Function<BufferWriter, Void> {

    /**
     * 通信channel对象
     */
    protected AsynchronousSocketChannel channel;

    /**
     * 读缓冲。
     */
    protected ByteBuffer readByteBuffer;
    /**
     * 写缓冲
     */
    protected ByteBuffer writeByteBuffer;
    /**
     * 输出信号量
     */
    private Semaphore semaphore = new Semaphore(1);

    /**
     * 读写回调
     */
    private ReadCompletionHandler readCompletionHandler;
    private WriteCompletionHandler writeCompletionHandler;

    /**
     * SSL服务
     */
    private SslHandler sslHandler;
    private IHandshakeCompletedListener handshakeCompletedListener;


    protected BufferWriter bufferWriter;

    private ChannelPipeline channelPipeline;


    /**
     * @param channel                通道
     * @param config                 配置
     * @param readCompletionHandler  读回调
     * @param writeCompletionHandler 写回调
     * @param chunkPool              内存池
     * @param channelPipeline        责任链
     */
    public AioChannel(AsynchronousSocketChannel channel, final BaseConfig config, ReadCompletionHandler readCompletionHandler, WriteCompletionHandler writeCompletionHandler, ChunkPool chunkPool, ChannelPipeline channelPipeline) {
        this.channel = channel;
        this.readCompletionHandler = readCompletionHandler;
        this.writeCompletionHandler = writeCompletionHandler;
        this.config = config;
        this.chunkPool = chunkPool;
        this.channelPipeline = channelPipeline;
        try {
            //初始化读缓冲区
            this.readByteBuffer = chunkPool.allocate(config.getReadBufferSize(), config.getChunkPoolBlockTime());
            //注意该方法可能抛异常
            channelPipeline.initChannel(this);
        } catch (Exception e) {
            try {
                channel.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            throw new RuntimeException("channelPipeline init exception", e);
        }

        //初始化数据输出类
        bufferWriter = new BufferWriter(BufferWriter.NOBLOCK, chunkPool, this, config.getBufferWriterQueueSize(), config.getChunkPoolBlockTime());

        //触发责任链
        try {
            invokePipeline(ChannelState.NEW_CHANNEL);
        } catch (Exception e) {
            logger.error(e);
        }
    }


    /**
     * 开始读取，很重要，只有调用该方法，才会开始监听消息读取
     */
    @Override
    public void starRead() {
        continueRead();
        if (this.sslHandler != null) {
            //若开启了SSL，则需要握手
            this.sslHandler.getSslService().beginHandshake(handshakeCompletedListener);
        }
    }


    /**
     * 立即关闭会话
     */
    @Override
    public synchronized void close() {


        if (status == CHANNEL_STATUS_CLOSED) {
            logger.warn("Channel:{} is closed:", getChannelId());
            return;
        }


        if (readByteBuffer != null) {
            chunkPool.deallocate(readByteBuffer);
        }

        if (writeByteBuffer != null) {
            chunkPool.deallocate(writeByteBuffer);
        }

        if (channelFutureListener != null) {
            channelFutureListener.operationComplete(this);
        }

        try {
            if (!bufferWriter.isClosed()) {
                bufferWriter.close();
            }
            bufferWriter = null;
        } catch (IOException e) {
            logger.error(e);
        }

        try {
            channel.shutdownInput();
        } catch (IOException e) {
            logger.debug(e.getMessage(), e);
        }
        try {
            channel.shutdownOutput();
        } catch (IOException e) {
            logger.debug(e.getMessage(), e);
        }
        try {
            channel.close();
        } catch (IOException e) {
            logger.error("close channel exception", e);
        }
        //更新状态
        status = CHANNEL_STATUS_CLOSED;
        //触发责任链通知
        try {
            invokePipeline(ChannelState.CHANNEL_CLOSED);
        } catch (Exception e) {
            e.printStackTrace();
        }

        //最后需要清空责任链
        if (defaultChannelPipeline != null) {
            defaultChannelPipeline.clean();
            defaultChannelPipeline = null;
        }

    }


    //--------------------------------------------------------------------------

    /**
     * 读取socket通道内的数据
     */
    protected void continueRead() {
        if (status == CHANNEL_STATUS_CLOSED) {
            return;
        }
        channel.read(readByteBuffer, this, readCompletionHandler);
    }


    /**
     * socket通道的读回调操作
     *
     * @param eof 状态回调标记
     */
    public void readFromChannel(boolean eof) {

        final ByteBuffer readBuffer = this.readByteBuffer;
        //读取缓冲区数据到管道
        if (null != readBuffer) {

            readBuffer.flip();
            //读取缓冲区数据，输送到责任链
            while (readBuffer.hasRemaining()) {
                byte[] bytes = new byte[readBuffer.remaining()];
                readBuffer.get(bytes, 0, bytes.length);
                try {
                    readToPipeline(bytes);
                } catch (Exception e) {
                    logger.error(e);
                    close();
                }
            }
            if (eof) {
                try {
                    invokePipeline(ChannelState.INPUT_SHUTDOWN);
                } catch (Exception e) {
                    logger.error(e);
                }
                close();
                return;
            }
            //触发读取完成，处理后续操作
            readCompleted(readBuffer);
        }
    }

    /**
     * socket读取完成
     *
     * @param readBuffer 读取的缓冲区
     */
    public void readCompleted(ByteBuffer readBuffer) {

        if (readBuffer == null) {
            return;
        }
        //数据读取完毕
        if (readBuffer.remaining() == 0) {
            //position = 0;limit = capacity;mark = -1;  有点初始化的味道，但是并不影响底层byte数组的内容
            readBuffer.clear();
        } else if (readBuffer.position() > 0) {
            //把从position到limit中的内容移到0到limit-position的区域内，position和limit的取值也分别变成limit-position、capacity。如果先将positon设置到limit，再compact，那么相当于clear()
            readBuffer.compact();
        } else {
            readBuffer.position(readBuffer.limit());
            readBuffer.limit(readBuffer.capacity());
        }
        //再次调用读取方法。循环监听socket通道数据的读取
        continueRead();
    }


//-------------------------------------------------------------------------------------------------

    /**
     * 写数据到责任链管道
     *
     * @param obj 写入的数据
     */
    @Override
    public void writeAndFlush(Object obj) {
        try {
            reverseInvokePipeline(ChannelState.CHANNEL_WRITE, obj);
        } catch (Exception e) {
            logger.error(e);
        }
    }

    /**
     * 写到BufferWriter输出器，不经过责任链
     *
     * @param obj 写入的数组
     */
    @Override
    public void writeToChannel(Object obj) {
        try {
            bufferWriter.writeAndFlush((byte[]) obj);
        } catch (IOException e) {
            logger.error(e);
        }
    }

    /**
     * 继续写
     *
     * @param writeBuffer 写入的缓冲区
     */
    private void continueWrite(ByteBuffer writeBuffer) {
        channel.write(writeBuffer, 0L, TimeUnit.MILLISECONDS, this, writeCompletionHandler);
    }


    /**
     * 写操作完成回调
     * 需要同步控制
     */
    public void writeCompleted() {

        if (writeByteBuffer == null) {
            writeByteBuffer = bufferWriter.poll();
        } else if (!writeByteBuffer.hasRemaining()) {
            //写完及时释放
            chunkPool.deallocate(writeByteBuffer);
            writeByteBuffer = bufferWriter.poll();
        }

        if (writeByteBuffer != null) {
            //再次写
            continueWrite(writeByteBuffer);
            //这里return是为了确保这个线程可以完全写完需要输出的数据。因此不释放信号量
            return;
        }
        //完全写完释放信息量
        semaphore.release();

        if (!keepAlive) {
            this.close();
        }

    }

    //-----------------------------------------------------------------------------------


    /**
     * 获取本地地址
     *
     * @return InetSocketAddress
     * @throws IOException 异常
     */
    @Override
    public final InetSocketAddress getLocalAddress() throws IOException {
        assertChannel();
        return (InetSocketAddress) channel.getLocalAddress();
    }

    /**
     * 获取远程地址
     *
     * @return InetSocketAddress
     * @throws IOException 异常
     */
    @Override
    public final InetSocketAddress getRemoteAddress() throws IOException {
        assertChannel();
        return (InetSocketAddress) channel.getRemoteAddress();
    }

    /**
     * 断言
     *
     * @throws IOException 异常
     */
    private void assertChannel() throws IOException {
        if (status == CHANNEL_STATUS_CLOSED || channel == null) {
            throw new IOException("channel is closed");
        }
    }


//--------------------------------------------------------------------------------------


    @Override
    public AsynchronousSocketChannel getAsynchronousSocketChannel() {
        return channel;
    }

    @Override
    public ChannelPipeline getChannelPipeline() {
        return channelPipeline;
    }

    /**
     * 设置SSLHandler
     *
     * @return AioChannel
     */
    @Override
    public void setSslHandler(SslHandler sslHandler) {
        this.sslHandler = sslHandler;
    }

    @Override
    public SslHandler getSslHandler() {
        return this.sslHandler;
    }


    @Override
    public void setSslHandshakeCompletedListener(IHandshakeCompletedListener handshakeCompletedListener) {
        this.handshakeCompletedListener = handshakeCompletedListener;
    }

    @Override
    public Void apply(BufferWriter input) {
        //获取信息量
        if (!semaphore.tryAcquire()) {
            return null;
        }
        AioChannel.this.writeByteBuffer = input.poll();
        if (null == writeByteBuffer) {
            semaphore.release();
        } else {
            AioChannel.this.continueWrite(writeByteBuffer);
        }
        return null;
    }


}
