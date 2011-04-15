/*
** AACPlayer - Freeware Advanced Audio (AAC) Player for Android
** Copyright (C) 2011 Spolecne s.r.o., http://www.spoledge.com
**  
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
** 
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
** 
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software 
** Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
**/
package com.spoledge.aacplayer;

import android.util.Log;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;

import java.net.URL;
import java.net.URLConnection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;


/**
 * This is the AACPlayer which uses AACDecoder to decode AAC stream into PCM samples.
 * Uses java.nio.* API.
 */
public class DirectAACPlayer extends AACPlayer {

    private static final String LOG = "DirectAACPlayer";


    ////////////////////////////////////////////////////////////////////////////
    // Constructors
    ////////////////////////////////////////////////////////////////////////////

    public DirectAACPlayer() {
    }


    ////////////////////////////////////////////////////////////////////////////
    // Public
    ////////////////////////////////////////////////////////////////////////////

    public void play( String url, Decoder decoder, PlayerCallback clb ) throws Exception {
        DirectDecoder ddecoder = (DirectDecoder) decoder;

        if (url.indexOf( ':' ) > 0) {
            URLConnection cn = new URL( url ).openConnection();
            cn.connect();

            dumpHeaders( cn );

            play( Channels.newChannel( cn.getInputStream()), ddecoder, clb ); 
        }
        else play( new FileInputStream( url ).getChannel(), ddecoder, clb );
    }


    ////////////////////////////////////////////////////////////////////////////
    // Private
    ////////////////////////////////////////////////////////////////////////////

    private void play( ReadableByteChannel rbc, DirectDecoder decoder, PlayerCallback clb ) throws Exception {
        if (clb != null) clb.playerStarted();

        if (rbc instanceof SelectableChannel) {
            try {
                ((SelectableChannel) rbc).configureBlocking( true );
            }
            catch (IOException e) {
                Log.e( LOG, "play(): cannot adjust blocking", e );
            }
        }
        else {
            Log.w( LOG, "play(): not selectable channel: " + rbc );
        }

        Log.i( LOG, "ByteOrder: " + ByteOrder.nativeOrder());

        //
        // NOTE: the buffer length must be adjusted to the expected stream bitrate/quality
        //       The higher bitrate, the higher buffer
        //       Experimental (this worked for http.yourmuze.com):
        //           24kbps: DirectBufferReader( 2048, 1024, 512, rbc )
        //           48kbps: DirectBufferReader( 8192, 4096, 1024, rbc )
        //           64kbps: DirectBufferReader( 8192, 4096, 1024, rbc )
        //          128kbps: DirectBufferReader( 16384, 8192, 2048, rbc )
        //
        DirectBufferReader reader = new DirectBufferReader( 2048, 1024, 512, rbc );
        //DirectBufferReader reader = new DirectBufferReader( 8192, 4096, 1024, rbc );
        //DirectBufferReader reader = new DirectBufferReader( 16384, 8192, 2048, rbc );

        new Thread( reader ).start();

        // try { Thread.sleep(500);} catch (InterruptedException e) {}

        stopped = false;

        DirectPCMFeed pcmfeed = null;

        // profiling info
        long profMs = 0;
        long profSamples = 0;
        long profSampleRate = 0;
        int profCount = 0;

        try {
            ByteBuffer inputBuffer = reader.next();
            Decoder.Info info = decoder.start( inputBuffer );

            Log.d( LOG, "play(): samplerate=" + info.getSampleRate() + ", channels=" + info.getChannels());
//if (info != null) throw new RuntimeException("Breakpoint");
            profSampleRate = info.getSampleRate() * info.getChannels();

            if (info.getChannels() > 2) {
                throw new RuntimeException("Too many channels detected: " + info.getChannels());
            }

            // 3 buffers for result samples:
            //   - one is used by decoder
            //   - one is used by the PCMFeeder
            //   - one is enqueued / passed to PCMFeeder - non-blocking op
            int samplesCapacity = info.getChannels() * info.getSampleRate() * 2;

            ByteBuffer[] bbuffers = new ByteBuffer[3];
            ShortBuffer[] buffers = new ShortBuffer[3];

            for (int i=0; i < buffers.length; i++) {
                ByteBuffer bb = bbuffers[i] = ByteBuffer.allocateDirect( 2*samplesCapacity );
                bb.order( ByteOrder.nativeOrder());
                buffers[i] = bb.asShortBuffer();
            }

            ByteBuffer outputBBuffer = bbuffers[0]; 
            ShortBuffer outputBuffer = buffers[0]; 

            int samplespoolindex = 0;

            pcmfeed = new DirectPCMFeed( info.getSampleRate(), info.getChannels());
            new Thread(pcmfeed).start();

            do {
                long tsStart = System.currentTimeMillis();

                /*
                outputBBuffer.put(0, (byte) 0xAB ); 
                outputBBuffer.put(1, (byte) 0xCD ); 

                if (inputBuffer.position()+1 < inputBuffer.limit())
                    Log.d( LOG,  "play(): BEFORE in[0]="
                        + Integer.toHexString( inputBuffer.get( inputBuffer.position()))
                        + ", in[1]=" + Integer.toHexString( inputBuffer.get( inputBuffer.position()+1)));

                Log.d( LOG,  "play(): BEFORE out_b[0]="
                    + Integer.toHexString(outputBBuffer.get(0))
                    + ", out_s[0]=" + Integer.toHexString( outputBuffer.get(0)));
                */

                int nsamp = decoder.decode( inputBuffer, outputBBuffer );

                /*
                if (outputBBuffer.limit() != 0)
                    Log.d( LOG,  "play(): AFTER out_b[0]="
                        + Integer.toHexString(outputBBuffer.get(0))
                        + ", out_s[0]=" + Integer.toHexString( outputBuffer.get(0)));
                */

                profMs += System.currentTimeMillis() - tsStart;
                profSamples += nsamp;
                profCount++;

                Log.d( LOG, "play(): decoded " + nsamp + " samples" );

                if (stopped) break;

                outputBuffer.position(0);
                outputBuffer.limit( nsamp > 0 ? nsamp : 0);

                pcmfeed.feed( outputBuffer );
                if (stopped) break;

                outputBuffer = buffers[ ++samplespoolindex % 3 ];
                outputBuffer.clear();

                outputBBuffer = bbuffers[ samplespoolindex % 3 ];
                outputBBuffer.clear();

                inputBuffer = reader.next();

                Log.d( LOG, "play(): yield, sleeping...");
                try { Thread.sleep( 50 ); } catch (InterruptedException e) {}
            } while (inputBuffer != null && !stopped);
        }
        finally {
            stopped = true;

            if (pcmfeed != null) pcmfeed.stop();
            decoder.stop();

            if (profCount > 0) Log.i( LOG, "play(): average decoding time: " + profMs / profCount + " ms");
            if (profMs > 0) Log.i( LOG, "play(): average rate (samples/sec): audio=" + profSampleRate
                + ", decoding=" + (1000*profSamples / profMs)
                + ", audio/decoding= " + (1000*profSamples / profMs - profSampleRate) * 100 / profSampleRate + " %  (the higher, the better; negative means that decoding is slower than needed by audio)");

            if (clb != null) clb.playerStopped();
        }
    }

}

