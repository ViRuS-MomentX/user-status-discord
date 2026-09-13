package ru.virus.voicebridge;

/**
 * Каталог не ответил.
 *
 * <p>Отличается от пустого результата: «ничего не нашлось» — это ответ, а сюда
 * попадают обрывы связи, таймауты и отказы сервиса. Путать их нельзя, иначе на
 * сетевую икоту пользователь читает, что его любимой песни не существует.
 */
public class CatalogUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CatalogUnavailableException(String message) {
        super(message);
    }
}
