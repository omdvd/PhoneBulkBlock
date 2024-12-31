package com.github.omdvd.phonebulkblock;

import android.telecom.Call;
import android.telecom.CallScreeningService;

import androidx.annotation.NonNull;

public class CallService extends CallScreeningService {
    @Override
    public void onScreenCall(@NonNull Call.Details details) {
        respondToCall(details, new CallResponse.Builder().build());
    }
}
