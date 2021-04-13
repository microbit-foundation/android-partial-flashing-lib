package org.microbit.android.partialflashing;

import android.app.Activity;
import android.app.IntentService;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;
import java.util.UUID;

import no.nordicsemi.android.ble.observer.ConnectionObserver;

import static org.microbit.android.partialflashing.PartialFlashingBLEManager.packetState;

/**
 * A class to communicate with and flash the micro:bit without having to transfer the entire HEX file
 * Created by samkent on 07/11/2017.
 */

// A service that interacts with the BLE device via the Android BLE API.
public abstract class PartialFlashingBaseService extends IntentService implements ConnectionObserver {

    public static final String DFU_BROADCAST_ERROR = "no.nordicsemi.android.dfu.broadcast.BROADCAST_ERROR";

    public static final String PXT_MAGIC = "708E3B92C615A841C49866C975EE5197";
    public static final String UPY_MAGIC = ".*FE307F59.{16}9DD7B1C1.*";

    private static final UUID MICROBIT_DFU_SERVICE = UUID.fromString("e95d93b0-251d-470a-a062-fa1922dfa9a8");
    private static final UUID MICROBIT_SECURE_DFU_SERVICE = UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb");
    private static final UUID MICROBIT_DFU_CHARACTERISTIC = UUID.fromString("e95d93b1-251d-470a-a062-fa1922dfa9a8");
    private static final UUID MICROBIT_SECURE_DFU_CHARACTERISTIC = UUID.fromString("8ec90004-f315-4f60-9fb8-838830daea50");

    private static final byte PACKET_STATE_WAITING = 0;
    private static final byte PACKET_STATE_SENT = (byte)0xFF;
    private static final byte PACKET_STATE_RETRANSMIT = (byte)0xAA;
    private static final byte PACKET_STATE_COMPLETE_FLASH = (byte) 0xCF;

    // Partial Flashing Intent Action Values
    private static final int PF_SUCCESS = 0x0;
    private static final int PF_ATTEMPT_DFU = 0x1;
    private static final int PF_FAILED = 0x2;
    private static final int PF_START = 0x3;

    public static final String BROADCAST_ACTION = "org.microbit.android.partialflashing.broadcast.BROADCAST_ACTION";

    // DAL Hash
    boolean python = false;

    private final static String TAG = PartialFlashingBaseService.class.getSimpleName();

    PartialFlashingBLEManager partialFlashingBLEManager;

    static final Object mObject = new Object();

    @Override
    public void onCreate() {
        super.onCreate();

        // Create intent filter and add to Local Broadcast Manager so that we can use an Intent to 
        // start the Partial Flashing Service
        
        final IntentFilter intentFilter = new IntentFilter();                           
        intentFilter.addAction(PartialFlashingBaseService.BROADCAST_ACTION);                       
        
        final LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.registerReceiver(broadcastReceiver, intentFilter);

    }

    public PartialFlashingBaseService() {
        super(TAG);
    }

    public static final String BROADCAST_PF_FAILED = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_FAILED";
    public static final String BROADCAST_PF_ATTEMPT_DFU = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_ATTEMPT_DFU";

    String filePath = null;

    void connect(@NonNull final BluetoothDevice device, String filepath) {
        partialFlashingBLEManager = new PartialFlashingBLEManager(getApplication());
        partialFlashingBLEManager.setConnectionObserver(this);
        Log.v(TAG, "Connect to: " + device.toString());
        partialFlashingBLEManager.connect(device).enqueue();

            synchronized (mObject) {
                try {
                    mObject.wait(10000);
                    Log.v(TAG, "End of wait()");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            int ret = attemptPartialFlash(filepath);
            Log.v(TAG, "attemptPartialFlash: " + ret);

            synchronized (mObject) {
                try {
                    mObject.wait(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            if(ret == PF_ATTEMPT_DFU) {
                partialFlashingBLEManager.enterDFU();
                partialFlashingBLEManager.disconnect().enqueue();
                LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(new Intent(BROADCAST_PF_ATTEMPT_DFU));

            } else if(ret == PF_FAILED) {
                partialFlashingBLEManager.disconnect();
                Intent intent = new Intent(BROADCAST_PF_FAILED);
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            }

    }

    protected void onHandleIntent(@Nullable Intent intent) {

        final String filePath = intent.getStringExtra("filepath");
        final String deviceAddress = intent.getStringExtra("deviceAddress");
        final int hardwareType = intent.getIntExtra("hardwareType", 1);
        final boolean pf = intent.getBooleanExtra("pf", true);

        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(deviceAddress);
        if(pf) {
            connect(device, filePath);
        }

        Log.v(TAG, "onHandleIntent End");
    }

    public int attemptPartialFlash(String filePath) {
        String dalHash = PartialFlashingBLEManager.getDalHash();
        if(dalHash == null) return PF_ATTEMPT_DFU;

        Log.v(TAG, "Flashing: " + filePath);
        long startTime = SystemClock.elapsedRealtime();

        int count = 0;
        int progressBar = 0;
        int numOfLines = 0;

        sendProgressBroadcastStart();

        try {

            Log.v(TAG, "attemptPartialFlash()");
            Log.v(TAG, filePath);
            HexUtils hex = new HexUtils(filePath);
            Log.v(TAG, "searchForData()");
            int magicIndex = hex.searchForData(PXT_MAGIC);

            if (magicIndex == -1) {
                magicIndex = hex.searchForDataRegEx(UPY_MAGIC);
                python = true;
            }

            Log.v(TAG, "/searchForData() = " + magicIndex);
            if (magicIndex > -1) {

                Log.v(TAG, "Found magic");

                if(dalHash == null) {
                    Log.v(TAG, "DAL HASH IS NULL");
                }

                // Find DAL hash
                if (python) magicIndex = magicIndex - 3;

                int record_length = hex.getRecordDataLengthFromIndex(magicIndex);
                Log.v(TAG, "Length of record: " + record_length);

                int magic_offset = (record_length == 64) ? 32 : 0;
                String hashes = hex.getDataFromIndex(magicIndex + ((record_length == 64) ? 0 : 1)); // Size of rows

                Log.v(TAG, "Hashes: " + hashes);

                if (!hashes.substring(magic_offset, magic_offset + 16).equals(dalHash)) {
                    Log.v(TAG, "No match: " + hashes.substring(magic_offset, magic_offset + 16) + " " + (dalHash));
                    return PF_ATTEMPT_DFU;
                }

                numOfLines = hex.numOfLines() - magicIndex;
                Log.v(TAG, "Total lines: " + numOfLines);

                // Ready to flash!
                // Loop through data
                String hexData;
                int packetNum = 0;
                int lineCount = 0;

                Log.v(TAG, "enter flashing loop");

                while (true) {
                    // Timeout if total is > 30 seconds
                    if (SystemClock.elapsedRealtime() - startTime > 60000) {
                        Log.v(TAG, "Partial flashing has timed out");
                        return PF_FAILED;
                    }

                    // Get next data to write
                    hexData = hex.getDataFromIndex(magicIndex + lineCount);

                    // Check if EOF
                    if (hex.getRecordTypeFromIndex(magicIndex + lineCount) != 0) break;

                    // Split into bytes
                    int offsetToSend = 0;
                    if (count == 0) {
                        offsetToSend = hex.getRecordAddressFromIndex(magicIndex + lineCount);
                    }

                    if (count == 1) {
                        offsetToSend = hex.getSegmentAddress(magicIndex + lineCount);
                    }

                    byte chunk[] = HexUtils.recordToByteArray(hexData, offsetToSend, packetNum);

                    // Write without response
                    // Wait for previous write to complete
                    partialFlashingBLEManager.writePartialFlash(chunk);

                    // Sleep after 4 packets
                    count++;
                    if (count == 4) {
                        count = 0;

                        // Wait for notification
                        Log.v(TAG, "Wait for notification");

                        // Send broadcast while waiting
                        int percent = Math.round((float) 100 * ((float) (lineCount) / (float) (numOfLines)));
                        sendProgressBroadcast(percent);

                        long timeout = SystemClock.elapsedRealtime();
                        while (packetState == PACKET_STATE_WAITING) {

                            // Timeout if longer than 5 seconds
                            if ((SystemClock.elapsedRealtime() - timeout) > 5000) return PF_FAILED;
                        }

                        packetState = PACKET_STATE_WAITING;

                        Log.v(TAG, "/Wait for notification");

                    } else {
                        Thread.sleep(9);
                    }

                    // If notification is retransmit -> retransmit last block.
                    // Else set start of new block
                    if (packetState == PACKET_STATE_RETRANSMIT) {
                        lineCount = lineCount - 4;
                    } else {
                        // Next line
                        lineCount = lineCount + 1;
                    }

                    // Always increment packet #
                    packetNum = packetNum + 1;

                }


                // Write End of Flash packet
                byte[] endOfFlashPacket = {(byte) 0x02};
                partialFlashingBLEManager.writePartialFlash(endOfFlashPacket);

                // Finished Writing
                Log.v(TAG, "Flash Complete");
                packetState = PACKET_STATE_COMPLETE_FLASH;
                sendProgressBroadcast(100);
                sendProgressBroadcastComplete();

                // Time execution
                long endTime = SystemClock.elapsedRealtime();
                long elapsedMilliSeconds = endTime - startTime;
                double elapsedSeconds = elapsedMilliSeconds / 1000.0;
                Log.v(TAG, "Flash Time: " + Float.toString((float) elapsedSeconds) + " seconds");

            } else {
                return PF_ATTEMPT_DFU;
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        partialFlashingBLEManager.disconnect();
        return PF_SUCCESS;
    }

    public static final String BROADCAST_PROGRESS = "org.microbit.android.partialflashing.broadcast.BROADCAST_PROGRESS";
    public static final String BROADCAST_START = "org.microbit.android.partialflashing.broadcast.BROADCAST_START";
    public static final String BROADCAST_COMPLETE = "org.microbit.android.partialflashing.broadcast.BROADCAST_COMPLETE";
    public static final String EXTRA_PROGRESS = "org.microbit.android.partialflashing.extra.EXTRA_PROGRESS";

    private void sendProgressBroadcast(final int progress) {

        Log.v(TAG, "Sending progress broadcast: " + progress + "%");

        final Intent broadcast = new Intent(BROADCAST_PROGRESS);
        broadcast.putExtra(EXTRA_PROGRESS, progress);

        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(broadcast);
    }

    private void sendProgressBroadcastStart() {

        Log.v(TAG, "Sending progress broadcast start");

        final Intent broadcast = new Intent(BROADCAST_START);

        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(broadcast);
    }

    private void sendProgressBroadcastComplete() {

        Log.v(TAG, "Sending progress broadcast complete");

        final Intent broadcast = new Intent(BROADCAST_COMPLETE);

        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(broadcast);
    }

    /* Receive updates on user interaction */
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, final Intent intent) {
            Log.v(TAG, "Received Broadcast: " + intent.toString());
        }
    };

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.v(TAG, "onDestroy");
        final LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.unregisterReceiver(broadcastReceiver);
    }
    
    protected abstract Class<? extends Activity> getNotificationTarget();
}

