package ru.virus.voicebridge;

import java.io.IOException;

/**
 * Языковая модель, которая сочиняет реплики пародии.
 */
public interface Llm {

    /**
     * Просит продолжение.
     *
     * @param system кем быть и как писать
     * @param prompt о чём речь сейчас
     * @return ответ модели; пустая строка, если она промолчала
     */
    String complete(String system, String prompt, int maxTokens) throws IOException;
}
