package com.openxc.sources;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import android.content.Context;
import android.util.Log;

import com.openxc.BinaryMessages;

/**
 * Common functionality for data sources that read a stream of newline-separated
 * messages in a separate thread from the main activity.
 */
public abstract class BytestreamDataSource extends ContextualVehicleDataSource
        implements Runnable {
    // TODO could let subclasses override this
    private final static int READ_BATCH_SIZE = 512;

    private AtomicBoolean mRunning = new AtomicBoolean(false);
    protected final ReadWriteLock mConnectionLock = new ReentrantReadWriteLock();
    private final Condition mDeviceChanged = mConnectionLock.writeLock().newCondition();
    private Thread mThread;
    private BytestreamConnectingTask mConnectionCheckTask;
    private PayloadFormat mCurrentPayloadFormat = null;
    private int mMessageCounter;

    private enum PayloadFormat {
        JSON, PROTO
    }

    public BytestreamDataSource(SourceCallback callback, Context context) {
        super(callback, context);
    }

    public BytestreamDataSource(Context context) {
        this(null, context);
    }

    public void start() {
        if(mRunning.compareAndSet(false, true)) {
            Log.d(getTag(), "Starting " + getTag() + " source");
            mThread = new Thread(this);
            mThread.start();
        }
    }

    public void stop() {
        if(mRunning.compareAndSet(true, false)) {
            Log.d(getTag(), "Stopping " + getTag() + " source");
            mThread.interrupt();
        }
    }

    /**
     * If not already connected to the data source, initiate the connection and
     * block until ready to be read.
     *
     * You must have the mConnectionLock locked before calling this
     * function.
     *
     * @throws DataSourceException The connection is still alive, but it
     *      returned an unexpected result that cannot be handled.
     * @throws InterruptedException if the interrupted while blocked -- probably
     *      shutting down.
     */
    protected void waitForConnection() throws InterruptedException {
        if(!isConnected() && mConnectionCheckTask == null) {
            mConnectionCheckTask = new BytestreamConnectingTask(this);
        }

        while(isRunning() && !isConnected()) {
            mConnectionLock.writeLock().lock();
            try {
                Log.d(getTag(), "Still no device available");
                mDeviceChanged.await();
            } finally {
                mConnectionLock.writeLock().unlock();
            }
        }

        mConnectionCheckTask = null;
    }

    public void run() {
        BytestreamBuffer buffer = new BytestreamBuffer();
        while(isRunning()) {
            try {
                waitForConnection();
            } catch(InterruptedException e) {
                Log.i(getTag(), "Interrupted while waiting for connection - stopping the source");
                stop();
                break;
            }

            int received;
            byte[] bytes = new byte[READ_BATCH_SIZE];
            try {
                received = read(bytes);
            } catch(IOException e) {
                Log.e(getTag(), "Unable to read response", e);
                disconnect();
                continue;
            }

            if(received == -1) {
                Log.e(getTag(), "Error on read - returned -1");
                disconnect();
                continue;
            }

            if(received > 0) {

                buffer.receive(bytes, received);
                if(mCurrentPayloadFormat == null || mMessageCounter == 0) {
                    if(buffer.containsJson()) {
                        mCurrentPayloadFormat = PayloadFormat.JSON;
                        Log.i(getTag(), "Source is sending JSON");
                    } else {
                        mCurrentPayloadFormat = PayloadFormat.PROTO;
                        Log.i(getTag(), "Source is sending protocol buffers");
                    }
                    mMessageCounter = 0;
                }

                switch(mCurrentPayloadFormat) {
                    case JSON:
                        for(String record : buffer.readLines()) {
                            handleMessage(record);
                            mMessageCounter++;
                        }
                        break;
                    case PROTO:
                        BinaryMessages.VehicleMessage message = null;
                        while((message = buffer.readBinaryMessage()) != null) {
                            handleMessage(message);
                            mMessageCounter++;
                        }
                        break;
                }
            }
        }
        disconnect();
        Log.d(getTag(), "Stopped " + getTag());
    }

    @Override
    public boolean isConnected() {
        return isRunning();
    }

    /**
     * Must have the connection lock before calling this function
     */
    protected void disconnected() {
        mDeviceChanged.signal();
        super.disconnected();
    }

    /**
     * Must have the connection lock before calling this function
     */
    protected void connected() {
        mDeviceChanged.signal();
        super.connected();
    }

    /**
     * Returns true if this source should be running, or if it should die.
     *
     * This is different than isConnected - they just happen to return the same
     * thing in this base data source.
     */
    protected boolean isRunning() {
        return mRunning.get();
    }

    /**
     * Read data from the source into the given array.
     *
     * No more than bytes.length bytes will be read, and there is no guarantee
     * that any bytes will be read at all.
     *
     * @param bytes the destination array for bytes from the data source.
     * @return the number of bytes that were actually copied into bytes.
     * @throws IOException if the source is unexpectedly closed or returns an
     *      error.
     */
    protected abstract int read(byte[] bytes) throws IOException;

    /**
     * Perform any cleanup necessary to disconnect from the interface.
     */
    protected abstract void disconnect();

    /** Initiate a connection to the vehicle interface. */
    protected abstract void connect() throws DataSourceException;
};
