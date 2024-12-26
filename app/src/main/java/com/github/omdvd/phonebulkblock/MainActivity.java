package com.github.omdvd.phonebulkblock;

import static android.provider.BlockedNumberContract.isBlocked;
import static android.provider.BlockedNumberContract.unblock;

import android.Manifest;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.BlockedNumberContract;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.karumi.dexter.Dexter;
import com.karumi.dexter.MultiplePermissionsReport;
import com.karumi.dexter.PermissionToken;
import com.karumi.dexter.listener.PermissionRequest;
import com.karumi.dexter.listener.multi.MultiplePermissionsListener;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity
    implements
        View.OnClickListener
{

    public static final String TAG = "MainActivity";

    private final int REQUEST_CODE_SET_DEFAULT_DIALER = 1001;
    private final int REQUEST_CODE_SHOW_SETTINGS_DLG = 1002;

    private final int ACTION_BLOCK_PATTERN = 0;
    private final int ACTION_CHECK_PATTERN = 1;
    private final int ACTION_UNBLOCK_PATTERN = 2;

    private ArrayList<String> mNumbersList;
    private Button mButtonDoBlock, mButtonDoCheck, mButtonDoStop, mButtonDoUnBlock;
    private EditText mInputPhoneNumber;
    private Handler mHandler;
    private ProgressBar mProgressAccessToList;
    private boolean mFlagStop;
    private int mCountProgress, mCountNumbersBlocked, mCountNumbersNonBlocked, mCountNumbersErrors;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        mButtonDoBlock = findViewById(R.id.button_do_block);
        mButtonDoCheck = findViewById(R.id.button_do_check);
        mButtonDoStop = findViewById(R.id.button_do_stop);
        mButtonDoUnBlock = findViewById(R.id.button_do_unblock);
        mInputPhoneNumber = findViewById(R.id.input_phone_number);
        mProgressAccessToList = findViewById(R.id.progress_bar);
        mProgressAccessToList.setVisibility(View.GONE);

        mButtonDoBlock.setOnClickListener(this);
        mButtonDoCheck.setOnClickListener(this);
        mButtonDoStop.setOnClickListener(this);
        mButtonDoUnBlock.setOnClickListener(this);

        mNumbersList = new ArrayList<>();
        mHandler = new Handler();

        mFlagStop = false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        checkDefaultDialer();

        Dexter.withContext(this)
                .withPermissions(
                        Manifest.permission.CALL_PHONE
                )
                .withListener(new MultiplePermissionsListener() {
                    @Override
                    public void onPermissionsChecked(MultiplePermissionsReport multiplePermissionsReport) {
                        if (multiplePermissionsReport.isAnyPermissionPermanentlyDenied()) {
                            showSettingsDialog();
                        }
                    }
                    @Override
                    public void onPermissionRationaleShouldBeShown(List<PermissionRequest> permissions, PermissionToken permissionToken) {
                        permissionToken.continuePermissionRequest();
                    }
        }).check();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SET_DEFAULT_DIALER) {
            checkSetDefaultDialerResult(resultCode);
        }
    }

    @Override
    public void onClick(View view) throws IllegalStateException {
        String phoneNumber = mInputPhoneNumber.getText().toString().trim();
        int id = view.getId();

        if (phoneNumber.isEmpty()) {
            Toast.makeText(MainActivity.this, "Please enter a valid phone number pattern", Toast.LENGTH_SHORT).show();
            return;
        }

        if (id == R.id.button_do_block) {
            doBLockListAction(phoneNumber, "*", ACTION_BLOCK_PATTERN);
        }
        else if (id == R.id.button_do_check) {
            doBLockListAction(phoneNumber, "*", ACTION_CHECK_PATTERN);
        }
        else if (id == R.id.button_do_stop) {
            mFlagStop = true;
        }
        else if (id == R.id.button_do_unblock) {
            doBLockListAction(phoneNumber, "*", ACTION_UNBLOCK_PATTERN);
        }
        else {
            throw new IllegalStateException("Unexpected value: " + getResources().getResourceName(id));
        }
    }

    private void checkDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = (RoleManager) getSystemService(ROLE_SERVICE);
            if (roleManager != null && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                Intent intentRequestRoleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                startActivityForResult(intentRequestRoleIntent, REQUEST_CODE_SET_DEFAULT_DIALER);
            }
        } else {
            TelecomManager telecomManager = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (telecomManager != null && !telecomManager.getDefaultDialerPackage().equals(getPackageName())) {
                Intent intentChangeDefaultDialer = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
                intentChangeDefaultDialer.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
                startActivity(intentChangeDefaultDialer);
            }
        }
    }

    private void checkSetDefaultDialerResult(int resultCode) {
        String message;
        switch (resultCode) {
            case RESULT_OK: message = "User accepted request to become default dialer"; break;
            case RESULT_CANCELED: message = "User declined request to become default dialer, application cannot work"; break;
            default: message = "Unexpected result code when checking default dealer: " + resultCode; break;
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * Work with phone blocklist - check, add or delete from phone blocklist numbers from given pattern
     *
     * @param inputPattern - pattern
     * @param placeHolder - placeholder
     * @param setAction - action with blocklist
     */
    private void doBLockListAction(String inputPattern, String placeHolder, int setAction) {
        mCountNumbersBlocked = 0;
        mCountNumbersNonBlocked = 0;
        mCountNumbersErrors = 0;
        mFlagStop = false;

        try {
            generateNumbersList(inputPattern, placeHolder, mNumbersList);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        mProgressAccessToList.setMax(mNumbersList.size());
        mProgressAccessToList.setVisibility(View.VISIBLE);
        setButtonInput(false);

        // Run slow task in another thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (mCountProgress = 0; mCountProgress < mNumbersList.size(); mCountProgress++) {
                    switch (setAction) {
                        case (ACTION_CHECK_PATTERN):
                            try {
                                if (isBlocked(MainActivity.this, mNumbersList.get(mCountProgress))) {
                                    mCountNumbersBlocked++;
                                } else {
                                    mCountNumbersNonBlocked++;
                                }
                            } catch (Exception e) {
                                mCountNumbersErrors++;
                            }
                            break;

                        case (ACTION_BLOCK_PATTERN):
                            ContentValues values = new ContentValues();
                            values.put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, mNumbersList.get(mCountProgress));
                            try {
                                if (!isBlocked(MainActivity.this, mNumbersList.get(mCountProgress))) {
                                    getContentResolver().insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values);
                                    mCountNumbersBlocked++;
                                } else {
                                    mCountNumbersNonBlocked++;
                                }
                            } catch (SecurityException e) {
                                mCountNumbersErrors++;
                            }
                            break;

                        case (ACTION_UNBLOCK_PATTERN):
                            try {
                                if (isBlocked(MainActivity.this, mNumbersList.get(mCountProgress))) {
                                    unblock(MainActivity.this, mNumbersList.get(mCountProgress));
                                    mCountNumbersBlocked++;
                                } else {
                                    mCountNumbersNonBlocked++;
                                }
                            } catch (Exception e) {
                                mCountNumbersErrors++;
                            }
                            break;
                    }

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mProgressAccessToList.setProgress(mCountProgress);
                        }
                    });

                    if (mFlagStop) {
                        break;
                    }
                }

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        String formattedItem;
                        switch (setAction) {
                            case (ACTION_CHECK_PATTERN):
                                formattedItem = String.format("%d numbers blocked, %d numbers not blocked.\n%d errors occured.", mCountNumbersBlocked, mCountNumbersNonBlocked, mCountNumbersErrors);
                                break;
                            case (ACTION_BLOCK_PATTERN):
                                formattedItem = String.format("%d numbers blocked, %d numbers already blocked.\n%d errors occured.", mCountNumbersBlocked, mCountNumbersNonBlocked, mCountNumbersErrors);
                                break;
                            case (ACTION_UNBLOCK_PATTERN):
                                formattedItem = String.format("%d numbers unblocked, %d numbers already not in block list.\n%d errors occured.", mCountNumbersBlocked, mCountNumbersNonBlocked, mCountNumbersErrors);
                                break;
                            default:
                                formattedItem = "Something went wrong";
                        }

                        showDialogOk("Result" ,formattedItem);
                        mProgressAccessToList.setProgress(0);
                        mProgressAccessToList.setVisibility(View.GONE);
                        setButtonInput(true);
                    }
                });
            }
        }).start();
    }

    /**
     * Call setting dialog
     */
    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setTitle("Need Permissions");
        builder.setMessage("This app needs permission `Phone`. You can grant them in app settings.");
        builder.setPositiveButton("GOTO SETTINGS", (dialog, which) -> {
            dialog.cancel();
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivityForResult(intent, REQUEST_CODE_SHOW_SETTINGS_DLG);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> {
            dialog.cancel();
        });
        builder.show();
    }

    /**
     * Show dialog with OK button
     *
     * @param title -  dialog title
     * @param message - dialog message
     */
    private void showDialogOk(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton("OK", (dialog, which) -> {
            dialog.cancel();
        });
        builder.show();
    }

    /**
     * Generate list of available numbers from pattern
     *
     * @param inputPattern - pattern
     * @param placeHolder - placeholder
     * @param generatedNumbers - list of generated numbers
     * @throws IllegalArgumentException - unsupported format of pattern or placeholder
     */
    private void generateNumbersList(String inputPattern, String placeHolder, ArrayList <String> generatedNumbers) throws IllegalArgumentException {
        int placeholdersCount;
        String pattern = inputPattern.trim();
        String patternPrefix = pattern.replaceAll("\\*", "").replaceAll("-", "");

        if (pattern.isEmpty()) {
            throw new IllegalArgumentException("Argument pattern is empty");
        }
        if (!pattern.matches("[0-9\\-+]+\\**")) {
            throw new IllegalArgumentException("Unsupported pattern format: " + pattern);
        }
        if (placeHolder.isEmpty()) {
            throw new IllegalArgumentException("Argument placeholder is empty");
        }

        generatedNumbers.clear();
        placeholdersCount = findPlaceholdersCount(pattern, placeHolder);

        if (placeholdersCount == 0) {
            generatedNumbers.add(pattern);
        } else {
            for (int i = 0; i < Math.pow(10, placeholdersCount); i++) {
                String formattedItem = String.format("%0" + placeholdersCount + "d", i);
                generatedNumbers.add(patternPrefix + formattedItem);
            }
        }
    }

    /**
     * Count placeholders in pattern
     *
     * @param inputPattern - pattern
     * @param placeHolder - placeholder
     * @return count of placeholders
     */
    private int findPlaceholdersCount(String inputPattern, String placeHolder) {
        int lastIndex = 0;
        int count = 0;

        while (lastIndex != -1) {
            lastIndex = inputPattern.indexOf(placeHolder, lastIndex);
            if (lastIndex != -1) {
                count++;
                lastIndex += placeHolder.length();
            }
        }
        return count;
    }

    /**
     * Enable or disable buttons
     *
     * @param setStatus - status for buttons
     */
    private void setButtonInput(boolean setStatus) {
        mButtonDoBlock.setEnabled(setStatus);
        mButtonDoCheck.setEnabled(setStatus);
        mButtonDoUnBlock.setEnabled(setStatus);
    }
}