package ru.virus.manager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Подопытный процесс для тестов: печатает строку, ждёт «stop» на входе.
 * С аргументом «crash» сразу падает с кодом 3, с «config» — с кодом 2.
 */
public final class EchoChild {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("crash")) {
            System.out.println("падаю");
            System.exit(3);
        }

        if (args.length > 0 && args[0].equals("config")) {
            System.out.println("12:00:00 [main] ERROR X - Ошибка настройки");
            System.exit(2);
        }

        System.out.println("привет из процесса ✓");
        System.out.flush();

        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;

        while ((line = in.readLine()) != null) {
            if (line.equals("stop")) {
                System.out.println("ухожу по-хорошему");
                System.exit(0);
            }
        }
    }
}
