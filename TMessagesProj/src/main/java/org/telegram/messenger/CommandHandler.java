package org.telegram.messenger;

import android.os.Handler;
import java.util.ArrayList;
import java.util.Collections;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import android.widget.Toast;
import android.content.Context;
import android.os.Looper;

public class CommandHandler {

    private static boolean invisibleMode = false;
    private static boolean autoReplyEnabled = false;
    private static String autoReplyMessage = null;
    private static boolean waitingForAutoReply = false;

    public static boolean handle(String text, long dialogId) {
        if (text == null || !text.startsWith("/")) return false;

        String[] parts = text.trim().split(" ", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/ping":
                handlePing(dialogId);
                return true;
            case "/calc":
                handleCalc(arg, dialogId);
                return true;
            case "/dice":
                handleDice(dialogId);
                return true;
            case "/coin":
                handleCoin(dialogId);
                return true;
            case "/8ball":
                handleEightBall(dialogId);
                return true;
            case "/invisible":
                handleInvisible(dialogId);
                return true;
            case "/help":
                handleHelp(dialogId);
                return true;
            case "/ghostping":
                handleGhostPing(arg, dialogId);
                return true;
            case "/autoreply":
                handleAutoReply(arg, dialogId);
                return true;
            case "/remind":
                handleRemind(arg, dialogId);
                return true;
            default:
                return false;
        }
    }

    private static void sendLocal(long dialogId, String text) {
        AndroidUtilities.runOnUIThread(() -> {
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, dialogId, null, null, null, false, null, null, null, false, 0, 0, null, false);
            SendMessagesHelper.getInstance(UserConfig.selectedAccount).sendMessage(params);
        });
    }

    private static void handleQr(String text, long dialogId) {
        if (text.isEmpty()) {
            sendLocal(dialogId, "❌ Формат: /qr <текст>");
            return;
        }
        try {
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
            com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
            com.google.zxing.common.BitMatrix matrix = writer.encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512, hints);
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.RGB_565);
            for (int x = 0; x < 512; x++) {
                for (int y = 0; y < 512; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? android.graphics.Color.BLACK : android.graphics.Color.WHITE);
                }
            }
            java.io.File file = new java.io.File(ApplicationLoader.applicationContext.getCacheDir(), "qr_" + System.currentTimeMillis() + ".jpg");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, fos);
            fos.close();
            AndroidUtilities.runOnUIThread(() -> {
                SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(file.getAbsolutePath(), dialogId, null, null, null, false, null, null, null, false, 0, 0, null, false);
                SendMessagesHelper.getInstance(UserConfig.selectedAccount).sendMessage(params);
            });
        } catch (Exception e) {
            sendLocal(dialogId, "❌ Ошибка генерации QR");
        }
    }

    private static void handlePing(long dialogId) {
        long start = System.currentTimeMillis();
        long ping = System.currentTimeMillis() - start;
        sendLocal(dialogId, "🏓 Пинг: " + ping + "ms");
    }

    private static void handleCalc(String expr, long dialogId) {
        try {
            double result = eval(expr);
            sendLocal(dialogId, result == (long)result ? String.valueOf((long)result) : String.valueOf(result));
        } catch (Exception e) {
            sendLocal(dialogId, "❌ Ошибка в выражении");
        }
    }

    private static void handleDice(long dialogId) {
        int result = (int)(Math.random() * 6) + 1;
        sendLocal(dialogId, "🎲 Выпало: " + result);
    }

    private static void handleCoin(long dialogId) {
        sendLocal(dialogId, Math.random() < 0.5 ? "🪙 Орёл" : "🪙 Решка");
    }

    private static void handleEightBall(long dialogId) {
        String[] answers = {
            "Да", "Нет", "Возможно", "Определённо нет",
            "Скорее всего", "Спроси позже", "Без сомнений"
        };
        String ans = answers[(int)(Math.random() * answers.length)];
        sendLocal(dialogId, "🎱 " + ans);
    }

    private static void handleInvisible(long dialogId) {
        invisibleMode = !invisibleMode;
        Toast.makeText(ApplicationLoader.applicationContext, invisibleMode
            ? "👻 Режим невидимки включён"
            : "👁 Режим невидимки выключён", Toast.LENGTH_SHORT).show();
    }

    private static void handleHelp(long dialogId) {
        String help =
            "/calc <выр> — калькулятор\n" +
            "/ping — пинг\n" +
            "/qr <текст> — QR-код\n" +
            "/weather <город> — погода\n" +
            "/remind <время> <текст> — напоминание\n" +
            "/dice — кубик\n" +
            "/coin — монетка\n" +
            "/8ball — магический шар\n" +
            "/chatstat — статистика чата\n" +
            "/topwords — топ слов\n" +
            "/activity — активность\n" +
            "/invisible — невидимка\n" +
            "/ghostping <сек> <текст> — самоудаляющееся\n" +
            "/autoreply on/off — автоответчик\n" +
            "/help — эта справка";
        sendLocal(dialogId, help);
    }

    private static void handleGhostPing(String arg, long dialogId) {
        String[] parts = arg.split(" ", 2);
        if (parts.length < 2) {
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, "❌ Формат: /ghostping <секунды> <сообщение>", Toast.LENGTH_SHORT).show());
            return;
        }
        try {
            int seconds = Integer.parseInt(parts[0]);
            String msg = parts[1];
            int[] sentMsgId = {0};
            SendMessagesHelper.getInstance(UserConfig.selectedAccount).sendMessage(
                SendMessagesHelper.SendMessageParams.of(msg, dialogId, null, null, null, false, null, null, null, false, 0, 0, null, false));
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
                if (sentMsgId[0] != 0) ids.add(sentMsgId[0]);
                MessagesController.getInstance(UserConfig.selectedAccount).deleteMessages(
                    ids, null, null, dialogId, false, 0, false, 0L, null, 0);
            }, seconds * 1000L);
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, "👻 Сообщение удалится через " + seconds + "с", Toast.LENGTH_SHORT).show());
        } catch (NumberFormatException e) {
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, "❌ Укажи число секунд", Toast.LENGTH_SHORT).show());
        }
    }

    private static void handleAutoReply(String arg, long dialogId) {
        if (arg.equals("on")) {
            autoReplyEnabled = true;
            sendLocal(dialogId, "✅ Автоответчик включён");
        } else if (arg.equals("off")) {
            autoReplyEnabled = false;
            sendLocal(dialogId, "❌ Автоответчик выключён");
        } else if (arg.isEmpty()) {
            waitingForAutoReply = true;
            sendLocal(dialogId, "💬 Что вы хотите поставить на автоответчик?");
        }
    }

    private static void handleRemind(String arg, long dialogId) {
        String[] parts = arg.split(" ", 2);
        if (parts.length < 2) {
            sendLocal(dialogId, "❌ Формат: /remind <секунды> <текст>");
            return;
        }
        try {
            int seconds = Integer.parseInt(parts[0]);
            String msg = parts[1];
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                sendLocal(dialogId, "⏰ Напоминание: " + msg), seconds * 1000L);
            sendLocal(dialogId, "✅ Напомню через " + seconds + "с");
        } catch (NumberFormatException e) {
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, "❌ Укажи число секунд", Toast.LENGTH_SHORT).show());
        }
    }

    // Простой eval для калькулятора
    private static double eval(String expr) {
        return new Object() {
            int pos = -1, ch;
            void nextChar() { ch = (++pos < expr.length()) ? expr.charAt(pos) : -1; }
            boolean eat(int c) {
                while (ch == ' ') nextChar();
                if (ch == c) { nextChar(); return true; }
                return false;
            }
            double parse() { nextChar(); double v = parseExpr(); if (pos < expr.length()) throw new RuntimeException(); return v; }
            double parseExpr() {
                double v = parseTerm();
                while (true) {
                    if (eat('+')) v += parseTerm();
                    else if (eat('-')) v -= parseTerm();
                    else return v;
                }
            }
            double parseTerm() {
                double v = parseFactor();
                while (true) {
                    if (eat('*')) v *= parseFactor();
                    else if (eat('/')) v /= parseFactor();
                    else return v;
                }
            }
            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();
                double v;
                int start = pos;
                if (eat('(')) { v = parseExpr(); eat(')'); }
                else {
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    v = Double.parseDouble(expr.substring(start, pos));
                }
                return v;
            }
        }.parse();
    }

    public static boolean isInvisibleMode() { return invisibleMode; }
    public static boolean isAutoReplyEnabled() { return autoReplyEnabled; }
    public static String getAutoReplyMessage() { return autoReplyMessage; }
    public static void setAutoReplyMessage(String msg) { autoReplyMessage = msg; }
    public static boolean isWaitingForAutoReply() { return waitingForAutoReply; }
    public static void setWaitingForAutoReply(boolean v) { waitingForAutoReply = v; }
}
