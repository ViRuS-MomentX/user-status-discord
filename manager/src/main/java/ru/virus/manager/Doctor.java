package ru.virus.manager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Доктор: читает консоль процесса и узнаёт известные поломки — «Telegram
 * закрыт», «Ollama не запущена», «вторая копия бота». Вместо сотни строк
 * WARN хозяин видит одну плашку: что сломалось и что нажать.
 *
 * <p>Проблема загорается, когда строка повторилась нужное число раз за окно
 * времени (единичный обрыв — не проблема), и гаснет сама, если такие строки
 * перестали появляться.
 */
final class Doctor {

    /** Через сколько тишины проблема считается ушедшей. */
    static final long QUIET_MS = 3 * 60_000L;

    /**
     * @param times    сколько совпадений за окно нужно, чтобы зажечь
     * @param window   окно, мс
     * @param action   что предложить: «config:ключ», «duplicates», «ollama» или пусто
     */
    record Rule(String id, Pattern pattern, int times, long window, String title, String advice, String action) {
    }

    record Issue(String id, String title, String advice, String action, long since, long last) {
    }

    static final List<Rule> RULES = List.of(
            new Rule("telegram", Pattern.compile("не достучался до Telegram|Telegram сейчас не отвечает"
                    + "|Telegram через .* не открывается"), 3, 5 * 60_000L,
                    "Telegram недоступен",
                    "Провайдер не пускает ни к api.telegram.org, ни к пересыльщику Cloudflare. Включи VPN "
                            + "для javaw.exe или впиши локальный прокси VPN-программы в bridge.telegram.proxy "
                            + "(например 127.0.0.1:10809).",
                    "config:bridge.telegram.proxy"),
            new Rule("ollama", Pattern.compile("Ollama не отвечает"), 1, 60_000L,
                    "Ollama не запущена",
                    "Бот не может сочинять реплики: приложение Ollama выключено.",
                    "ollama"),
            new Rule("ollama-model", Pattern.compile("в Ollama нет модели"), 1, 60_000L,
                    "В Ollama нет нужной модели",
                    "Скачай модель командой из консоли (строка «ollama pull …») или поменяй parody.llm.model.",
                    "config:parody.llm.model"),
            new Rule("ollama-slow", Pattern.compile("Ollama думала дольше"), 2, 30 * 60_000L,
                    "Модель слишком тяжёлая",
                    "Ollama не успевает ответить. Возьми модель поменьше (например gemma3:4b).",
                    "config:parody.llm.model"),
            new Rule("copy", Pattern.compile("забирает кто-то ещё|вторая копия|Conflict: terminated by other"),
                    1, 5 * 60_000L,
                    "Запущена вторая копия бота",
                    "Две копии с одним токеном мешают друг другу: сообщения достаются то одной, то другой.",
                    "duplicates"),
            new Rule("discord-token", Pattern.compile("Discord не принял токен"), 1, 60_000L,
                    "Discord не принял токен",
                    "Токен бота сброшен или вписан с ошибкой. Возьми новый на discord.com/developers → Bot.",
                    "config:bot.token"),
            new Rule("telegram-token", Pattern.compile("Telegram отказал( пародийному боту)?: .*(Unauthorized|Not Found)"),
                    1, 60_000L,
                    "Telegram не принял токен",
                    "Токен бота в Telegram неверный или отозван. Возьми его у @BotFather.",
                    "config:parody.telegram.token"),
            new Rule("port", Pattern.compile("(?i)failed to bind to port|address already in use"), 1, 60_000L,
                    "Порт сервера занят",
                    "Скорее всего, работает вторая копия сервера. Останови лишнюю или поменяй server-port.",
                    "duplicates"),
            new Rule("memory", Pattern.compile("OutOfMemoryError"), 1, 10 * 60_000L,
                    "Не хватает памяти",
                    "Java упёрлась в предел памяти. Увеличь -Xmx в команде процесса (кнопка «Изменить»).",
                    ""));

    private final Map<String, Deque<Long>> hits = new HashMap<>();
    private final Map<String, Issue> issues = new LinkedHashMap<>();

    /**
     * Строка консоли.
     *
     * @return проблема, которая только что загорелась; null — ничего нового
     */
    synchronized Issue feed(String line, long time) {
        for (var rule : RULES) {
            if (!rule.pattern().matcher(line).find()) {
                continue;
            }

            var times = hits.computeIfAbsent(rule.id(), id -> new ArrayDeque<>());
            times.addLast(time);

            while (!times.isEmpty() && time - times.getFirst() > rule.window()) {
                times.removeFirst();
            }

            var known = issues.get(rule.id());

            if (known != null) {
                issues.put(rule.id(), new Issue(known.id(), known.title(), known.advice(), known.action(),
                        known.since(), time));
                return null;
            }

            if (times.size() >= rule.times()) {
                var issue = new Issue(rule.id(), rule.title(), rule.advice(), rule.action(), time, time);
                issues.put(rule.id(), issue);
                return issue;
            }
        }

        return null;
    }

    /** Что не так сейчас, свежее первым. Затихшие проблемы снимаются. */
    synchronized List<Issue> active(long now) {
        issues.values().removeIf(issue -> now - issue.last() > QUIET_MS);
        var result = new ArrayList<>(issues.values());
        result.sort((a, b) -> Long.compare(b.last(), a.last()));
        return result;
    }

    /** Хозяин нажал «Понятно» или перезапустил процесс — начинаем наблюдать заново. */
    synchronized void clear() {
        issues.clear();
        hits.clear();
    }
}
