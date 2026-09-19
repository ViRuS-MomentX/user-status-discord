// Пересыльщик к Telegram Bot API для Cloudflare Workers.
//
// Нужен там, где api.telegram.org недоступен напрямую: бот стучится сюда, а Worker
// живёт в сети Cloudflare, откуда Telegram открыт. Для бота это выглядит как свой
// сервер Bot API — тот же адрес прописывается в bridge.telegram.api.
//
// Как поставить:
//   1. dash.cloudflare.com → Workers & Pages → Create → Worker
//   2. Вставить этот текст, нажать Deploy
//   3. Settings → Variables → добавить секрет TOKEN со значением токена бота
//   4. Полученный адрес вида https://имя.ваш-логин.workers.dev вписать в настройки:
//      bridge.telegram.api=https://имя.ваш-логин.workers.dev

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // Пересылаем только то, что относится к Bot API: вызовы методов и выдачу файлов.
    // Всё прочее — не наше дело, и открывать через себя произвольные адреса незачем
    const isApi = url.pathname.startsWith('/bot');
    const isFile = url.pathname.startsWith('/file/bot');

    if (!isApi && !isFile) {
      return new Response('Это пересыльщик к Telegram Bot API, больше тут ничего нет.', {
        status: 404,
        headers: { 'content-type': 'text/plain; charset=utf-8' }
      });
    }

    // Адрес Worker'а угадать несложно, а открытый пересыльщик к Telegram быстро
    // найдут и займут чужими ботами. Пускаем только свой токен
    if (env.TOKEN && !url.pathname.includes(env.TOKEN)) {
      return new Response('Чужой токен здесь не обслуживается.', {
        status: 403,
        headers: { 'content-type': 'text/plain; charset=utf-8' }
      });
    }

    const target = 'https://api.telegram.org' + url.pathname + url.search;

    // Запрос пересылаем как есть: и заголовки, и тело. Так через пересыльщик
    // проходят и обычные вызовы, и отправка файлов многочастной формой
    return fetch(new Request(target, request));
  }
};
