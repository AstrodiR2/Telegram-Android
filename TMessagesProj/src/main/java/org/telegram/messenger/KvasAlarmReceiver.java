package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class KvasAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        java.util.Set<Long> chats = CommandHandler.getAiUserChats();
        if (chats == null || chats.isEmpty()) return;
        for (long dialogId : chats) {
            if (!AiManager.canSendQuoteToday(context, dialogId)) continue;
            AiManager.generateQuote(context, dialogId);
        }
    }
}
