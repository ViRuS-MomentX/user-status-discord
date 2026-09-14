package ru.virus.voicebridge;

/**
 * Настройки панели: картинки и слова, по которым собираются сезонные подборки.
 *
 * <p>Слова вынесены в настройки, потому что подходящий запрос зависит от каталога и
 * вкуса: кому-то нужен «christmas», кому-то «новогодние песни».
 *
 * @param image ссылка на картинку-подсказку внутри карточки плеера
 * @param legend файл с той же подсказкой на диске; бот прикладывает его сам, и ссылка
 *               тогда не нужна
 * @param sheet файл с листом иконок 4×4, из которого бот заводит эмодзи кнопок
 */
public record PanelSettings(String image, String legend, String sheet,
                            String winter, String spring, String autumn, String summer) {
}
