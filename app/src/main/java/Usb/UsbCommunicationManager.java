package Usb;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.*;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Timer;
import java.util.TimerTask;

// https://stackoverflow.com/questions/19736301/android-usb-host-read-from-device
public class UsbCommunicationManager {

    private static final String ACTION_USB_PERMISSION = "com.jimdo.dominicdj.USB_PERMISSION";
    private static int TIMEOUT = 3000;
    private boolean forceClaim = true;

    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbInterface intf;
    private UsbEndpoint receiveEndpoint, sendEndpoint; // receiveEndpoint: from device to android; sendEndpoint: from
    // android to device
    private UsbDeviceConnection connection;

    private PendingIntent usbPermissionIntent;
    private Context context;
    private boolean isReceiverRegistered = false;

    private Timer recvTimer;
    private RecvBuffer recvBuffer;

    private static final String TAG = UsbCommunicationManager.class.getSimpleName();

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_USB_PERMISSION)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (device != null) {
                        if (setUpConnection()) {
                            connection = usbManager.openDevice(usbDevice);
                            connection.claimInterface(intf, forceClaim);
                        }

                    }
                } else {
                    Log.d(TAG, "Permission denied for device " + device);
                }
            }
        }
    };

    private final BroadcastReceiver detachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                Log.d(TAG, "USB device detached.");
                Toast.makeText(context, "MIDIDevice detached...", Toast.LENGTH_SHORT).show();
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    releaseUsb();
                }
            }
        }
    };

    public UsbCommunicationManager(Context context) {
        this.context = context;
        usbManager = (UsbManager) context.getSystemService(context.USB_SERVICE);

        // needed to later on ask permission from user to use the usb device
        usbPermissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), 0);

        context.registerReceiver(detachReceiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));
        if (!isReceiverRegistered) {
            context.registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION));
            isReceiverRegistered = true;
        }

        recvTimer = new Timer();
    }

    public void createRecvBuffer(RecvBuffer.BufferChangeListener changeListener) {
        this.recvBuffer = new RecvBuffer(changeListener);
    }

    /**
     * @return list of all plugged in usb devices
     */
    public String[] getAvailableDevicesVendorsIds() {
        Collection<UsbDevice> usbDevices = usbManager.getDeviceList().values(); // add listener, so that app will
        // note when a cable was plugged in/out
        String[] devicesVendors = new String[usbDevices.size()];

        int i = 0;
        for (UsbDevice usbDevice : usbDevices) {
            devicesVendors[i++] = String.valueOf(usbDevice.getVendorId());
        }

        return devicesVendors;
    }

    public String[] getDevicesInfo(UsbDevice[] usbDevices) {
        // TODO: add listener for when device is attached/detached

        String[] devicesInfo = new String[usbDevices.length];
        int i = 0;
        for (UsbDevice usbDevice : usbDevices) {
            // TODO: parse values, see http://www.linux-usb.org/usb.ids and https://stackoverflow
            StringBuilder buffer = new StringBuilder();
            buffer.append(usbDevice.getVendorId());
            buffer.append(" ");
            buffer.append(usbDevice.getProductId());

            devicesInfo[i++] = buffer.toString();
        }

        return devicesInfo;
    }

    public UsbDevice[] getAvailableDevices() {
        Collection<UsbDevice> values = usbManager.getDeviceList().values();
        return values.toArray(new UsbDevice[values.size()]);
    }

    public boolean connectToUsbDevice(UsbDevice usbDevice) {
        this.usbDevice = usbDevice; // this is the device the user selected (in the RecyclerView)
        usbManager.requestPermission(usbDevice, usbPermissionIntent); // user must approve of connection
        Log.d(TAG, "Has Permission? " + usbManager.hasPermission(usbDevice));
        return usbManager.hasPermission(usbDevice); // check if user got permission
    }

    /**
     * Find the right interface and endpoints for the communication.
     * <p>See <a href="https://de.wikipedia.org/wiki/Universal_Serial_Bus#Bulk-Transfer">USB (Wikipedia)</a>
     */
    private boolean setUpConnectionOld() {
        /*for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
            if (usbDevice.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) {*/
        intf = usbDevice.getInterface(0); // there is only one interface on the Tyros 3
        Log.d(TAG, "Set interface: " + intf);

        // find the endpoints
        for (int j = 0; j < intf.getEndpointCount(); j++) {
            int direction = intf.getEndpoint(j).getDirection();
            int type = intf.getEndpoint(j).getType();

            if (direction == UsbConstants.USB_DIR_OUT && type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                sendEndpoint = intf.getEndpoint(j); // from android to device
                Log.d(TAG, "Set sendEndpoint endpoint: " + sendEndpoint);
            } else if (direction == UsbConstants.USB_DIR_IN && type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                receiveEndpoint = intf.getEndpoint(j); // from device to android
                Log.d(TAG, "Set receiveEndpoint endpoint: " + receiveEndpoint);
            }
        }

        Log.d(TAG,
                "SetUpConnection finished, will return: " + (intf != null && sendEndpoint != null && receiveEndpoint != null));
        return intf != null && sendEndpoint != null && receiveEndpoint != null;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private boolean setUpConnection() {
        // ====================================================================================================
        // ONLY FOR TYROS3!!!
        // ====================================================================================================
        intf = usbDevice.getInterface(0);
        Log.d(TAG, "setUpConnection with Interface: " + intf + "(sum up endpoints)");
        sendEndpoint = intf.getEndpoint(0);
        receiveEndpoint = intf.getEndpoint(1);
        return true;
        // when does it return false? not good practice to rely on exceptions here
    }

    private boolean isConnectionProperlySetUp() {
        /*Log.d(TAG,
                "==========================ProperlySetUp?\ninterface: " + intf + "\n\nreceiveEndpoint endpoint: " +
                receiveEndpoint
                        + "\nsendEndpoint endpoint: " + sendEndpoint + "\n\nconnection: " + connection + "\n" +
                        "==========================");*/
        return usbDevice != null && intf != null && receiveEndpoint != null && sendEndpoint != null
                && connection != null;
    }

    public void releaseUsb() {
        if (isReceiverRegistered) {
            context.unregisterReceiver(usbReceiver);
            isReceiverRegistered = false;
        }
        if (intf != null && connection != null) {
            connection.releaseInterface(intf);
            connection.close();
        }
        recvTimer.cancel();
        Log.d(TAG, "Connection successfully closed");
    }

    @TargetApi(Build.VERSION_CODES.O)
    public boolean send(final byte[] bytes) {
        // https://stackoverflow.com/questions/42023388/no-response-from-android-bulktransfer-with-proper-endpoints
        if (!isConnectionProperlySetUp()) {
//            Log.d(TAG, "In send, but connection not properly set up ;-(");
//            Toast.makeText(context, "No properly set up connection!", Toast.LENGTH_SHORT).show();
            return false;
        }

        // https://stackoverflow.com/questions/12345953/android-usb-host-asynchronous-interrupt-transfer
        // could help

        String hexMessage = Utils.Conversion.toHexString(bytes);
        new Thread(new Runnable() {
            @Override
            public void run() {
                connection.bulkTransfer(sendEndpoint, bytes, bytes.length, TIMEOUT);
            }
        }).start();

        Log.d(TAG, "Successfully sent message: " + hexMessage);
        // Toast.makeText(context, "Sent message: " + hexMessage, Toast.LENGTH_SHORT).show();

        return true;
    }


    public boolean startReceive() {
        if (!isConnectionProperlySetUp()) {
//            Log.d(TAG, "In receive, but connection not properly set up ;-(");
            return false;
        }
//        Log.d(TAG, "In receive and connection properly set up ;)");
//        Log.d(TAG, "USB Manager has access? " + String.valueOf(usbManager.hasPermission(usbDevice)));

        // reinitialize read value byte array
        //Arrays.fill(readBytes, (byte) 0);
        //recvBytes = 0;

        // maybe helpful:
        // https://stackoverflow.com/questions/12345953/android-usb-host-asynchronous-interrupt-transfer
        class MidiUsbRequest extends TimerTask {
            @Override
            public void run() {
                // TODO: if recvBuffer == null

                ByteBuffer buffer = ByteBuffer.allocate(512);
                UsbRequest request = new UsbRequest();
                request.initialize(connection, receiveEndpoint);

                // queue an inbound request on the bulk transfer endpoint
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    request.queue(buffer); // TODO: replace with new api
                } else {
                    request.queue(buffer, 512);
                }
                // wait for event (confirmation that request was completed)
                if (connection.requestWait() == request) {
                    recvBuffer.setBuffer(buffer);
                    Log.d(TAG, "USB request successfully completed.");
                } else {
                    Log.d(TAG, "requestWait failed!");
                }
            }
        }

        recvTimer.schedule(new MidiUsbRequest(), 0, 10); // TODO: maybe adjust this value

        // =================================
        // OLD
        // =================================

        /*// wait for some data from the device
        new Thread(new Runnable() {
            @Override
            public void run() {
                recvBytes = connection.bulkTransfer(receiveEndpoint, readBytes, readBytes.length, TIMEOUT);
            }
        });

//        connection.bulkTransfer(receiveEndpoint, readBytes, readBytes.length, TIMEOUT);
        Log.d(TAG, "Bulk transfer done.");

        if (recvBytes > 0) {
            Log.d(TAG, "Received some data: " + readBytes.toString());
            Toast.makeText(context, "Received: " + readBytes.toString(), Toast.LENGTH_SHORT).show();
        } else {
            Log.d(TAG, "Did not receive any data, recvBytes: " + recvBytes);
            Toast.makeText(context, "Did not receive any data", Toast.LENGTH_SHORT).show();
        }

        // return String.valueOf(recvBytes);*/

        return true;
    }

}