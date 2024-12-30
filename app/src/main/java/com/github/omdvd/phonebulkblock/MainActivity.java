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
import android.util.Pair;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
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
    private Button mButtonDoBlock, mButtonDoCheck, mButtonDoStop, mButtonDoUnBlock;
    private EditText mInputPhoneNumber;
    private Handler mHandler;
    private ProgressBar mProgressAccessToList;
    private String mPatternPrefix, mCurrentNumber;
    private boolean mFlagShowStatus, mFlagStop;
    private int mNumbersCount, mPlaceholdersCount, mCountNumber, mCountProgress, mProgressDiv, mCountNumbersBlocked, mCountNumbersNonBlocked, mCountNumbersErrors;
    private long mDurationStart;

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

        mHandler = new Handler();

        mFlagShowStatus = false;
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
            mFlagShowStatus = true;
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
        int tmpProgressMax;

        try {
            Pair<Integer, String> patternData = doParsePattern(inputPattern, placeHolder);
            mPlaceholdersCount = patternData.first;
            mPatternPrefix = patternData.second;
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        mNumbersCount = (int) Math.pow(10, mPlaceholdersCount);

        // On big number lists reduce setProgress() calls to 100 per task
        if (mNumbersCount <= 100) {
            mProgressDiv = 1;
            tmpProgressMax = mNumbersCount;
        } else {
            mProgressDiv = (int)Math.pow(10, (mPlaceholdersCount - 2));
            tmpProgressMax = 100;
        }

        mCountNumbersBlocked = 0;
        mCountNumbersNonBlocked = 0;
        mCountNumbersErrors = 0;
        mCountProgress = mProgressDiv;
        mFlagStop = false;
        mProgressAccessToList.setMax(tmpProgressMax);
        mProgressAccessToList.setProgress(0);
        mProgressAccessToList.setVisibility(View.VISIBLE);
        mDurationStart = System.currentTimeMillis();
        setButtonInput(false);

        // Run slow task in another thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (mCountNumber = 0; mCountNumber < mNumbersCount; mCountNumber++) {
                    mCurrentNumber = String.format("%s%0" + mPlaceholdersCount + "d", mPatternPrefix, mCountNumber);
                    switch (setAction) {
                        case (ACTION_CHECK_PATTERN):
                            try {
                                if (isBlocked(MainActivity.this, mCurrentNumber)) {
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
                            values.put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, mCurrentNumber);
                            try {
                                if (!isBlocked(MainActivity.this, mCurrentNumber)) {
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
                                if (isBlocked(MainActivity.this, mCurrentNumber)) {
                                    unblock(MainActivity.this, mCurrentNumber);
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
                            mCountProgress--;
                            if (mCountProgress == 0) {
                                mProgressAccessToList.setProgress(mProgressAccessToList.getProgress() + 1);
                                mCountProgress = mProgressDiv;
                            }
                        }
                    });

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (mFlagShowStatus) {
                                long timeFromStart = (System.currentTimeMillis() - mDurationStart);
                                float workSpeed = ((float)mCountNumber) / ((float)timeFromStart / 1000f);
                                long estimatedTime = (long)((float)((mNumbersCount - mCountNumber) * 1000l) / workSpeed);
                                String formattedItem = String.format("Numbers total: %d, processed: %d, errors: %d.\nElapsed time: %s\nSpeed: %.1f numbers/s\nEstimated time: %s", mNumbersCount, mCountNumber, mCountNumbersErrors, formatSecondsToTime(timeFromStart), workSpeed, formatSecondsToTime(estimatedTime));
                                showDialogOkCancel("Progress", formattedItem);
                                mFlagShowStatus = false;
                            }
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
                                formattedItem = String.format("%d numbers blocked, %d numbers not blocked.\n%d errors occured.\nElapsed time: %s", mCountNumbersBlocked, mCountNumbersNonBlocked, mCountNumbersErrors, formatSecondsToTime(System.currentTimeMillis() - mDurationStart));
                                break;
                            case (ACTION_BLOCK_PATTERN):
                                formattedItem = String.format("%d numbers blocked, %d numbers already blocked.\n%d errors occured.\nElapsed time: %s", mCountNumbersBlocked, mCountNumbersNonBlocked, mCountNumbersErrors, formatSecondsToTime(System.currentTimeMillis() - mDurationStart));
                                break;
                            case (ACTION_UNBLOCK_PATTERN):
                                formattedItem = String.format("%d numbers unblocked, %d numbers already not in block list.\n%d errors occured.\nElapsed time: %s", mCountNumbersBlocked, mCountNumbersNonBlocked, mCountNumbersErrors, formatSecondsToTime(System.currentTimeMillis() - mDurationStart));
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
     * Show dialog with Continue and Stop buttons
     *
     * @param title -  dialog title
     * @param message - dialog message
     */
    private void showDialogOkCancel(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton("Continue", (dialog, which) -> {
            dialog.cancel();
        });
        builder.setNegativeButton("Stop", (dialog, which) -> {
            mFlagStop = true;
            dialog.cancel();
        });
        builder.show();
    }

    /**
     * Validate input pattern, count placeholders and select number prefix from pattern
     *
     * @param inputPattern - pattern
     * @param inputPlaceHolder - placeholder
     * @return - Pair of count of placeholders and number prefix
     * @throws IllegalArgumentException - unsupported format of pattern or placeholder or placeholders count
     */
    private Pair<Integer, String> doParsePattern(@NonNull String inputPattern, @NonNull String inputPlaceHolder)  throws IllegalArgumentException {
        int placeholdersCount;

        String pattern = inputPattern.trim();
        String placeHolder = inputPlaceHolder.trim();

        if (pattern.isEmpty()) {
            throw new IllegalArgumentException("Argument pattern is empty");
        }
        if (placeHolder.isEmpty()) {
            throw new IllegalArgumentException("Argument placeholder is empty");
        }
        if (placeHolder.length() > 1) {
            throw new IllegalArgumentException("Argument placeholder too long");
        }
        if (!pattern.matches("[0-9\\-+]+" + "\\" + placeHolder + "*")) {
            throw new IllegalArgumentException("Unsupported pattern format: " + pattern);
        }

        placeholdersCount = findPlaceholdersCount(pattern, placeHolder);
        if (placeholdersCount > 7) {
            throw new IllegalArgumentException("Too many placeholders");
        }
        if (placeholdersCount == 0) {
            throw new IllegalArgumentException("Missing placeholders");
        }
        String patternPrefix = pattern.replaceAll("\\" + placeHolder, "").replaceAll("-", "");
        return new Pair<Integer, String>(placeholdersCount, patternPrefix);
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
     * Format string with text presentation of time interval in milliseconds
     *
     * @param millis - time interval in milliseconds
     * @return string with text
     */
    private String formatSecondsToTime(long millis) {
        long mil = millis % 1000;
        long sec = millis / 1000;
        long seconds = sec % 60;
        long minutes = sec / 60;
        if (minutes >= 60) {
            long hours = minutes / 60;
            minutes %= 60;
            if( hours >= 24) {
                long days = hours / 24;
                return String.format("%d days %02dh:%02dm:%02ds", days,hours%24, minutes, seconds);
            }
            return String.format("%02dh:%02dm:%02ds", hours, minutes, seconds);
        }
        return String.format("%02dm:%02ds", minutes, seconds);
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