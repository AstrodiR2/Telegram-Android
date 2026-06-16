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
import org.telegram.messenger.AiManager;

public class CommandHandler {

    private static boolean invisibleMode = false;
    private static boolean autoReplyEnabled = false;
    private static String autoReplyMessage = null;
    private static boolean waitingForAutoReply = false;

    // AI wizard state
    public static final int AI_WIZARD_NONE = 0;
    public static final int AI_WIZARD_URL = 1;
    public static final int AI_WIZARD_MODEL = 2;
    public static final int AI_WIZARD_TOKEN = 3;
    private static int aiWizardStep = AI_WIZARD_NONE;
    private static String aiWizardUrl = "";
    private static String aiWizardModel = "";
    private static long aiWizardDialogId = 0;

    public static boolean handle(String text, long dialogId) {
        if (text == null || !text.startsWith("/")) return false;

        String[] parts = text.trim().split(" ", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/id":
                handleId(dialogId);
                return true;
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
                return true;
            case "/autoreply":
                handleAutoReply(arg, dialogId);
                return true;
            case "/remind":
                handleRemind(arg, dialogId);
                return true;
            case "/ai":
                handleAi(arg, dialogId);
                return true;
            case "/exit":
                handleExit(dialogId);
                return true;
            case "/qr":
            case "/chatstat":
            case "/topwords":
            case "/activity":
            case "/weather":
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

    private static void handleId(long dialogId) {
        long myId = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
        sendLocal(dialogId, "🆔 Твой ID: " + myId);
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
        if (invisibleMode) {
            ConnectionsManager.getInstance(UserConfig.selectedAccount).setAppPaused(true, false);
        } else {
            ConnectionsManager.getInstance(UserConfig.selectedAccount).setAppPaused(false, false);
        }
        Toast.makeText(ApplicationLoader.applicationContext, invisibleMode
            ? "👻 Режим невидимки включён"
            : "👁 Режим невидимки выключён", Toast.LENGTH_SHORT).show();
    }

    private static void handleHelp(long dialogId) {
        String help =
            "/id — твой Telegram ID\n" +
            "/calc <выр> — калькулятор\n" +
            "/ping — пинг\n" +
            "/remind <сек> <текст> — напоминание\n" +
            "/dice — кубик\n" +
            "/coin — монетка\n" +
            "/8ball — магический шар\n" +
            "/invisible — невидимка\n" +

            "/autoreply on/off — автоответчик\n" +
            "/ai <вопрос> — спросить AI\n" +
            "/ai api — настроить AI\n" +
            "/ai role — сменить роль AI\n" +
            "/exit — выйти из режима настройки\n" +
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
            AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(ApplicationLoader.applicationContext, "💬 Напишите текст автоответчика:", android.widget.Toast.LENGTH_SHORT).show());
        } else {
            AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(ApplicationLoader.applicationContext, "❌ Формат: /autoreply on/off или просто /autoreply", android.widget.Toast.LENGTH_SHORT).show());
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

    private static void handleAi(String arg, long dialogId) {
        android.content.Context ctx = ApplicationLoader.applicationContext;
        if (arg.trim().equals("api")) {
            aiWizardStep = AI_WIZARD_URL;
            aiWizardDialogId = dialogId;
            aiWizardUrl = "";
            aiWizardModel = "";
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Отправьте URL провайдера:\n(Например https://generativelanguage.googleapis.com/v1beta)", Toast.LENGTH_LONG).show());
            return;
        }
        if (arg.trim().equals("role")) {
            int newRole = AiManager.nextRole(ctx);
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Роль: " + AiManager.getRoleName(newRole), Toast.LENGTH_SHORT).show());
            return;
        }
        // /ai <вопрос>
        if (arg.trim().isEmpty()) {
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "❌ Формат: /ai <вопрос>", Toast.LENGTH_SHORT).show());
            return;
        }
        if (!AiManager.isConfigured(ctx)) {
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "❌ AI не настроен. Используй /ai api", Toast.LENGTH_SHORT).show());
            return;
        }
        AiManager.ask(ctx, arg.trim(), new AiManager.AiCallback() {
            @Override
            public void onResult(String result) {
                sendLocal(dialogId, result);
            }
            @Override
            public void onError(String error) {
                AndroidUtilities.runOnUIThread(() ->
                    Toast.makeText(ctx, error, Toast.LENGTH_LONG).show());
            }
        });
    }

    private static void handleExit(long dialogId) {
        android.content.Context ctx = ApplicationLoader.applicationContext;
        if (aiWizardStep != AI_WIZARD_NONE) {
            aiWizardStep = AI_WIZARD_NONE;
            aiWizardUrl = "";
            aiWizardModel = "";
            aiWizardDialogId = 0;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "✅ Вышел из режима настройки AI", Toast.LENGTH_SHORT).show());
        } else {
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "❌ Ты не в режиме настройки", Toast.LENGTH_SHORT).show());
        }
    }

    // Вызывается из SendMessagesHelper для перехвата wizard шагов
    public static boolean handleAiWizardStep(String text, long dialogId) {
        if (aiWizardStep == AI_WIZARD_NONE) return false;
        if (text.startsWith("/")) return false;
        android.content.Context ctx = ApplicationLoader.applicationContext;
        if (aiWizardStep == AI_WIZARD_URL) {
            aiWizardUrl = text.trim();
            aiWizardStep = AI_WIZARD_MODEL;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Модель ИИ:\n(Например gemini-2.5-flash)", Toast.LENGTH_LONG).show());
            return true;
        }
        if (aiWizardStep == AI_WIZARD_MODEL) {
            aiWizardModel = text.trim();
            aiWizardStep = AI_WIZARD_TOKEN;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Свой API ключ:", Toast.LENGTH_LONG).show());
            return true;
        }
        if (aiWizardStep == AI_WIZARD_TOKEN) {
            String token = text.trim();
            AiManager.saveSettings(ctx, aiWizardUrl, aiWizardModel, token);
            aiWizardStep = AI_WIZARD_NONE;
            aiWizardUrl = "";
            aiWizardModel = "";
            aiWizardDialogId = 0;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "✅ AI настроен успешно!", Toast.LENGTH_SHORT).show());
            return true;
        }
        return false;
    }

    public static int getAiWizardStep() { return aiWizardStep; }

    public static boolean isInvisibleMode() { return invisibleMode; }
    public static boolean isAutoReplyEnabled() { return autoReplyEnabled; }
    public static String getAutoReplyMessage() { return autoReplyMessage; }
    public static void setAutoReplyMessage(String msg) { autoReplyMessage = msg; }
    public static boolean isWaitingForAutoReply() { return waitingForAutoReply; }
    public static void setWaitingForAutoReply(boolean v) { waitingForAutoReply = v; }
}
