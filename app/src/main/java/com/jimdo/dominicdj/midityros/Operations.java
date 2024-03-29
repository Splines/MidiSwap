package com.jimdo.dominicdj.midityros;

import Usb.RecvBuffer;
import Usb.UsbCommunicationManager;
import Utils.Conversion;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.*;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Operations extends AppCompatActivity implements RecvBuffer.BufferChangeListener {

    private static UsbCommunicationManager usbCommunicationManager;

    private static final String TAG = Operations.class.getSimpleName();

    private EditText inputMsgEditText;
    private EditText outputMsgEditText;

    String inputMsg; // without whitespaces, extra characters, all in UPPERCASE
    String outputMsg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_operations);

        inputMsgEditText = findViewById(R.id.edit_text_input_msg);
        outputMsgEditText = findViewById(R.id.edit_text_output_msg);
        restrictText();

        usbCommunicationManager = MainActivity.getUsbCommunicationManager();
        usbCommunicationManager.createRecvBuffer(this);
    }

    private void restrictText() {
        InputFilter[] filters = new InputFilter[2];
        filters[0] = new InputFilter.AllCaps();
        filters[1] = new InputFilter() {
            @Override
            public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
                // although there is a AllCaps-filter include small caps letters as well in this regex
                if (source.length() == 0 || source.toString().matches("[ \\da-fA-FxX]+")) {
                    return null; // accept the original replacement
                } else {
                    return ""; // do not accept
                }
            }
        };
        inputMsgEditText.setFilters(filters);
        outputMsgEditText.setFilters(filters);

        // Format in HEX code with whitespace after every second character
        addTextWatcher(inputMsgEditText);
        addTextWatcher(outputMsgEditText);
    }

    private void addTextWatcher(final EditText editText) {
        TextWatcher hexTextWatcherOut = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // remove spacing char except if at favored position (in front of every third character)
                if ((s.length() % 3) != 0) {
                    if (s.charAt(s.length() - 1) == ' ') {
                        s.delete(s.length() - 1, s.length());
                    }
                }
                // Insert space where needed: in front of every third character
                if (s.length() > 0 && (s.length() % 3) == 0 && s.charAt(s.length()-1) != ' ') {
                    s.insert(s.length() - 1, " ");
                }
            }
        };
        editText.addTextChangedListener(hexTextWatcherOut);
    }

    public void onSendMessage(View v) {
        String hexMessage = "1991257A"; // Standard message
        if (v.getId() == R.id.btn_send_on) {
            hexMessage = "19913A7A"; // NOTE ON, channel 1, note 3A, volume: 7A
        } else if (v.getId() == R.id.btn_send_off) {
            hexMessage = "18813A7A";
        }

        if (usbCommunicationManager.send(Utils.Conversion.toByteArray(hexMessage))) {
            Toast.makeText(this, "Sent message", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Message sent failed", Toast.LENGTH_SHORT).show();
        }
    }

    public void onReceiveMessage(View v) {
        usbCommunicationManager.startReceive();
    }

    @Override
    protected void onStop() {
        super.onStop();
        usbCommunicationManager.releaseUsb();
    }

    @Override
    public void onBackPressed() {
        usbCommunicationManager.releaseUsb();
        Toast.makeText(this, "Connection closed", Toast.LENGTH_SHORT).show();
        super.onBackPressed();
    }

    public void updateMsg(View view) {
        // Check user input
        // remove all whitespaces and non-visible characters (e. g. tab, \n) and only use small caps
        // although we already restricted the editorTexts to allow only UPPERCASE, it doesn't hurt to convert
        // everything to UPPERCASE letters again (Better safe than sorry...)
        String inputMsgRaw = inputMsgEditText.getText().toString().replaceAll("\\s+", "").toUpperCase();
        String outputMsgRaw = outputMsgEditText.getText().toString().replaceAll("\\s+", "").toUpperCase();

        if (inputMsgRaw.length() < 6 || outputMsgRaw.length() < 6) {
            Toast.makeText(this, "Each of the massages has to be at least three bytes long!",
                    Toast.LENGTH_LONG).show();
        } else {
            int inCount = countChar(inputMsgRaw, 'X');
            int outCount = countChar(outputMsgRaw, 'X');
            if (inCount > 2 || outCount > 2) {
                Toast.makeText(this, "You have to many X in your message!", Toast.LENGTH_LONG).show();
                return;
            }
            inputMsg = inputMsgRaw;
            outputMsg = outputMsgRaw;
            Toast.makeText(this, "Updated", Toast.LENGTH_SHORT).show();
        }
    }

    private int countChar(String string, Character charToCompare) {
        int count = 0;
        for (Character c : string.toCharArray()) {
            if (c == charToCompare) {
                count++;
            }
        }
        return count;
    }

    public void onUpdateByte(ByteBuffer byteBuffer) {
        String data = Conversion.toHexString(byteBuffer.array());
        Log.d(TAG, data);
        data = data.replaceAll("\\s+", "");

        // NOTES
        /*Pattern p = Pattern.compile("9\\d [0-9a-fA-F]{2} [0-9a-fA-F]{2}"); // e. g. 90 XX XX
        Matcher m = p.matcher(data);
        if (m.find()) {
            int startIndex = m.start();
            String noteValue = data.substring(startIndex + 3, startIndex + 5);
            Log.d(TAG, "Keyy pressed: " + data);
            Log.d(TAG, "Note: " + noteValue);

            if (!data.substring(startIndex + 5, startIndex + 7).equals("00")) { // pressure
                String msg = "1BB14A" + noteValue;
                usbCommunicationManager.send(Conversion.toByteArray(msg));
            }
        }*/

        /*// MODULATION WHEEL
        Pattern p = Pattern.compile("(?i)B1 01 [\\da-f]{2}"); // e. g. B1 01 XX (00-7F)
        Matcher m = p.matcher(data);
        if (m.find()) {
            String modulationData = m.group();
            Log.d(TAG, "Found modulation wheel byte: " + modulationData);
            String hexValue = modulationData.substring(6, 8);
            String msg = "1BB14A" + hexValue;
            usbCommunicationManager.send(Conversion.toByteArray(msg));
        }*/

        // CUSTOM
        // don't do anything if user hasn't already set inputMsg or outputMsg
        if (inputMsg == null || outputMsg == null) {
            return;
        }
        // check for XX values in input and adjust regex pattern accordingly
        String regexPattern = inputMsg;
        Matcher substituteX = Pattern.compile("XX").matcher(regexPattern);
        Log.d(TAG, "regexPattern (before): " + regexPattern);
        int subIndex = 0;
        if (substituteX.find()) {
            subIndex = substituteX.start();
            regexPattern = regexPattern.replace("XX", "[\\da-fA-F]{2}");
            Log.d(TAG, "regexPattern (after): " + regexPattern);
        }
        // search in data from usb
        Matcher m = Pattern.compile(regexPattern).matcher(data);
        if (m.find()) {
            String dataValueToReplace = m.group().substring(subIndex, subIndex + 2);
            Log.d(TAG, "Found interesting data: " + dataValueToReplace);
            // check for XX values in output
            // // in case: replace XX in output with respective value in data (usb) at position of XX in input
            // TODO: check if there is after all a dataValuetoReplace (if there is XX in input)
            String msg = outputMsg.replace("XX", dataValueToReplace);
            Log.d(TAG, "Send message: " + msg);
            usbCommunicationManager.send(Conversion.toByteArray(msg));
        }
    }
}