/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package cn.jesson.nettyclient.decode;

import androidx.annotation.NonNull;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.TooLongFrameException;
import cn.jesson.nettyclient.utils.NettyLogUtil;

import java.util.List;

public class Socks5DelimiterBasedFrameDecoder extends LocalByteToMessageDecoder {
    private final String TAG = "Socks5DelimiterBasedFrameDecoder";
    private final ByteBuf[] delimiters;
    private final int maxFrameLength;
    private final boolean stripDelimiter;
    private final boolean failFast;
    private boolean discardingTooLongFrame;
    private int tooLongFrameLength;
    /**
     * Set only when decoding with "\n" and "\r\n" as the delimiter.
     */
    private final LocalLineBasedFrameDecoder lineBasedDecoder;

    private boolean mProxy;


    public Socks5DelimiterBasedFrameDecoder(int maxFrameLength, ByteBuf delimiter) {
        this(maxFrameLength, true, delimiter);
    }


    public Socks5DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, ByteBuf delimiter) {
        this(maxFrameLength, stripDelimiter, true, delimiter);
    }

    public Socks5DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, boolean failFast,
            ByteBuf delimiter) {
        this(maxFrameLength, stripDelimiter, failFast, new ByteBuf[]{
                delimiter.slice(delimiter.readerIndex(), delimiter.readableBytes())});
    }


    public Socks5DelimiterBasedFrameDecoder(int maxFrameLength, ByteBuf... delimiters) {
        this(maxFrameLength, true, delimiters);
    }


    public Socks5DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, ByteBuf... delimiters) {
        this(maxFrameLength, stripDelimiter, true, delimiters);
    }


    public Socks5DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, boolean failFast, ByteBuf... delimiters) {
        validateMaxFrameLength(maxFrameLength);
        if (delimiters == null) {
            throw new NullPointerException("delimiters");
        }
        if (delimiters.length == 0) {
            throw new IllegalArgumentException("empty delimiters");
        }

        if (isLineBased(delimiters) && !isSubclass()) {
            lineBasedDecoder = new LocalLineBasedFrameDecoder(maxFrameLength, stripDelimiter, failFast);
            this.delimiters = null;
        } else {
            this.delimiters = new ByteBuf[delimiters.length];
            for (int i = 0; i < delimiters.length; i++) {
                ByteBuf d = delimiters[i];
                validateDelimiter(d);
                this.delimiters[i] = d.slice(d.readerIndex(), d.readableBytes());
            }
            lineBasedDecoder = null;
        }
        this.maxFrameLength = maxFrameLength;
        this.stripDelimiter = stripDelimiter;
        this.failFast = failFast;
    }

    /**
     * Returns true if the delimiters are "\n" and "\r\n".
     */
    private static boolean isLineBased(final ByteBuf[] delimiters) {
        if (delimiters.length != 2) {
            return false;
        }
        ByteBuf a = delimiters[0];
        ByteBuf b = delimiters[1];
        if (a.capacity() < b.capacity()) {
            a = delimiters[1];
            b = delimiters[0];
        }
        return a.capacity() == 2 && b.capacity() == 1
                && a.getByte(0) == '\r' && a.getByte(1) == '\n'
                && b.getByte(0) == '\n';
    }

    /**
     * Return {@code true} if the current instance is a subclass of DelimiterBasedFrameDecoder
     */
    private boolean isSubclass() {
        return getClass() != Socks5DelimiterBasedFrameDecoder.class;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param ctx    the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param buffer the {@link ByteBuf} from which to read data
     * @return frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     * be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        if (mProxy) {
            return buffer.readSlice(buffer.readableBytes()).retain();
        } else {
            if (lineBasedDecoder != null) {
                return lineBasedDecoder.decode(ctx, buffer);
            }
            // Try all delimiters and choose the delimiter which yields the shortest frame.
            int minFrameLength = Integer.MAX_VALUE;
            ByteBuf minDelim = null;
            for (ByteBuf delim : delimiters) {
                int frameLength = indexOf(buffer, delim);
                if (frameLength >= 0 && frameLength < minFrameLength) {
                    minFrameLength = frameLength;
                    minDelim = delim;
                }
            }

            if (minDelim != null) {
                int minDelimLength = minDelim.capacity();
                ByteBuf frame;

                if (discardingTooLongFrame) {
                    // We've just finished discarding a very large frame.
                    // Go back to the initial state.
                    discardingTooLongFrame = false;
                    buffer.skipBytes(minFrameLength + minDelimLength);

                    int tooLongFrameLength = this.tooLongFrameLength;
                    this.tooLongFrameLength = 0;
                    if (!failFast) {
                        fail(tooLongFrameLength);
                    }
                    return null;
                }

                if (minFrameLength > maxFrameLength) {
                    // Discard read frame.
                    buffer.skipBytes(minFrameLength + minDelimLength);
                    fail(minFrameLength);
                    return null;
                }

                if (stripDelimiter) {
                    frame = buffer.readSlice(minFrameLength);
                    buffer.skipBytes(minDelimLength);
                } else {
                    frame = buffer.readSlice(minFrameLength + minDelimLength);
                }

                return frame.retain();
            } else {
                if (!discardingTooLongFrame) {
                    if (buffer.readableBytes() > maxFrameLength) {
                        // Discard the content of the buffer until a delimiter is found.
                        tooLongFrameLength = buffer.readableBytes();
                        buffer.skipBytes(buffer.readableBytes());
                        discardingTooLongFrame = true;
                        if (failFast) {
                            fail(tooLongFrameLength);
                        }
                    }
                } else {
                    // Still discarding the buffer since a delimiter is not found.
                    tooLongFrameLength += buffer.readableBytes();
                    buffer.skipBytes(buffer.readableBytes());
                }
                return null;
            }
        }
    }

    private void fail(long frameLength) {
        if (frameLength > 0) {
            throw new TooLongFrameException(
                    "frame length exceeds " + maxFrameLength +
                            ": " + frameLength + " - discarded");
        } else {
            throw new TooLongFrameException(
                    "frame length exceeds " + maxFrameLength +
                            " - discarding");
        }
    }

    /**
     * Returns the number of bytes between the readerIndex of the haystack and
     * the first needle found in the haystack.  -1 is returned if no needle is
     * found in the haystack.
     */
    private static int indexOf(ByteBuf haystack, ByteBuf needle) {
        for (int i = haystack.readerIndex(); i < haystack.writerIndex(); i++) {
            int haystackIndex = i;
            int needleIndex;
            for (needleIndex = 0; needleIndex < needle.capacity(); needleIndex++) {
                if (haystack.getByte(haystackIndex) != needle.getByte(needleIndex)) {
                    break;
                } else {
                    haystackIndex++;
                    if (haystackIndex == haystack.writerIndex() &&
                            needleIndex != needle.capacity() - 1) {
                        return -1;
                    }
                }
            }

            if (needleIndex == needle.capacity()) {
                // Found the needle from the haystack!
                return i - haystack.readerIndex();
            }
        }
        return -1;
    }

    private static void validateDelimiter(ByteBuf delimiter) {
        if (delimiter == null) {
            throw new NullPointerException("delimiter");
        }
        if (!delimiter.isReadable()) {
            throw new IllegalArgumentException("empty delimiter");
        }
    }

    private static void validateMaxFrameLength(int maxFrameLength) {
        if (maxFrameLength <= 0) {
            throw new IllegalArgumentException(
                    "maxFrameLength must be a positive integer: " +
                            maxFrameLength);
        }
    }

    @Override
    public void notifyProxyStateChange(boolean state) {
        NettyLogUtil.d(TAG, "proxyStateChange::state is: " + state);
        mProxy = state;
    }

    public static class Builder {
        private Socks5DelimiterBasedFrameDecoder decoder;

        public Builder(int maxFrameLength, @NonNull String delimiter) {
            ByteBuf byteBuf = Unpooled.copiedBuffer(delimiter.getBytes());
            decoder = new Socks5DelimiterBasedFrameDecoder(maxFrameLength, byteBuf);
        }

        public Builder setProxyState(boolean proxy) {
            decoder.mProxy = proxy;
            return this;
        }

        public Socks5DelimiterBasedFrameDecoder build() {
            return decoder;
        }
    }


}
