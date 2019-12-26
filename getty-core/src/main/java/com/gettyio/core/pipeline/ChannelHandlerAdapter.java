/**
 * 包名：org.getty.core.pipeline
 * 版权：Copyright by www.getty.com
 * 描述：
 * 邮箱：189155278@qq.com
 * 时间：2019/9/27
 */
package com.gettyio.core.pipeline;


import com.gettyio.core.channel.AioChannel;
import com.gettyio.core.channel.ChannelState;
import com.gettyio.core.handler.timeout.IdleState;

/**
 * 类名：ChannelHandlerAdapter.java
 * 描述：handler 抽像父类，in out父类需继承该类
 * 修改人：gogym
 * 时间：2019/9/27
 */
public abstract class ChannelHandlerAdapter implements ChannelboundHandler {

    /**
     * 该方法类似一个心态起搏器，执行读或写操作会被触发
     *
     * @param aioChannel 通道
     * @param evt        IdleState
     * @throws Exception 异常
     */
    public abstract void userEventTriggered(AioChannel aioChannel, IdleState evt) throws Exception;

}
