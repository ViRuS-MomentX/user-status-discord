package ru.virus.voicebridge;

/**
 * Источник звука не ответил.
 *
 * <p>Отличается от «ничего не нашлось» тем, что виновата не песня, а связь: перебирать
 * дальше по списку бессмысленно, каждая попытка стоит ещё полминуты ожидания.
 *
 * <p>Непроверяемое нарочно: летит сквозь CompletableFuture, а тот проверяемых не носит.
 */
public final class AudioUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AudioUnavailableException(String message) {
        super(message);
    }
}
