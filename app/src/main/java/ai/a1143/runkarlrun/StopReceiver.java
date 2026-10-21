package ai.a1143.runkarlrun;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The notification's Stop action. stopService() has no background-start restriction. */
public class StopReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        context.stopService(new Intent(context, CatService.class));
    }
}
