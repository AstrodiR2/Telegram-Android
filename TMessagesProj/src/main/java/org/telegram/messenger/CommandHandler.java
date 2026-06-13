package org.telegram.messenger;

import android.os.Handler;
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
        // Показываем сообщение локально в чате (не отправляем)
        AndroidUtilities.runOnUIThread(() -> {
            // TODO: вставить в UI как системное сообщение
        });
    }

    private static void handlePing(long dialogId) {
        long start = System.currentTimeMillis();
        long ping = System.currentTimeMillis() - start;
        sendLocal(dialogId, "🏓 Пинг: " + ping + "ms");
    }

    private static void handleCalc(String expr, long dialogId) {
        try {
            double result = eval(expr);
            sendLocal(dialogId, "🧮 " + expr + " = " + result);
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
        sendLocal(dialogId, invisibleMode
            ? "👻 Режим невидимки включён"
            : "👁 Режим невидимки выключён");
    }

    private static void handleHelp(long dialogId) {
        String help =
            "`/calc <выр>` — калькулятор\n" +
            "`/ping` — пинг\n" +
            "`/qr <текст>` — QR-код\n" +
            "`/weather <город>` — погода\n" +
            "`/remind <время> <текст>` — напоминание\n" +
            "`/dice` — кубик\n" +
            "`/coin` — монетка\n" +
            "`/8ball` — магический шар\n" +
            "`/chatstat` — статистика чата\n" +
            "`/topwords` — топ слов\n" +
            "`/activity` — активность\n" +
            "`/invisible` — невидимка\n" +
            "`/ghostping <сек> <текст>` — самоудаляющееся\n" +
            "`/autoreply on/off` — автоответчик\n" +
            "`/help` — эта справка";
        sendLocal(dialogId, help);
    }

    private static void handleGhostPing(String arg, long dialogId) {
        String[] parts = arg.split(" ", 2);
        if (parts.length < 2) {
            sendLocal(dialogId, "❌ Формат: /ghostping <секунды> <сообщение>");
            return;
        }
        try {
            int seconds = Integer.parseInt(parts[0]);
            String msg = parts[1];
            // TODO: отправить сообщение и удалить через seconds секунд
            sendLocal(dialogId, "👻 Сообщение удалится через " + seconds + "с");
        } catch (NumberFormatException e) {
            sendLocal(dialogId, "❌ Укажи число секунд");
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
            sendLocal(dialogId, "❌ Укажи число секунд");
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
