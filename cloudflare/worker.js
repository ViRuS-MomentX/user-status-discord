// Пересыльщик к Telegram Bot API для Cloudflare Workers.
//
// Нужен там, где api.telegram.org недоступен напрямую: бот стучится сюда, а Worker
// живёт в сети Cloudflare, откуда Telegram открыт. Для бота это выглядит как свой
// сервер Bot API — тот же адрес прописывается в bridge.telegram.api.
//
// Как поставить:
//   1. dash.cloudflare.com → Workers & Pages → Create → Start with Hello World!
//   2. Вставить этот текст, нажать Deploy
//   3. Settings → Variables and Secrets → добавить секрет TOKEN со значением токена бота
//   4. Открыть https://имя.ваш-логин.workers.dev/check — он сам проверит связь и токен
//   5. Полученный адрес вписать в настройки:
//      bridge.telegram.api=https://имя.ваш-логин.workers.dev

const TELEGRAM = 'https://api.telegram.org';

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // Проверка связи. Токен берётся из секрета, а не из ссылки: так его негде
    // опечатать и он не остаётся в истории команд. Отвечаем словами, потому что
    // смотрит сюда человек, а не бот
    if (url.pathname === '/check') return check(env);

    // Пересылаем только то, что относится к Bot API: вызовы методов и выдачу файлов.
    // Всё прочее — не наше дело, и открывать через себя произвольные адреса незачем
    const isApi = url.pathname.startsWith('/bot');
    const isFile = url.pathname.startsWith('/file/bot');

    if (!isApi && !isFile) {
      return say('Это пересыльщик к Telegram Bot API, больше тут ничего нет.\n'
        + 'Проверить связь: /check', 404);
    }

    // Адрес Worker'а угадать несложно, а открытый пересыльщик к Telegram быстро
    // найдут и займут чужими ботами. Пускаем только свой токен
    if (env.TOKEN && !url.pathname.includes(env.TOKEN)) {
      return say('Чужой токен здесь не обслуживается.', 403);
    }

    // Запрос пересылаем как есть: и заголовки, и тело. Так через пересыльщик
    // проходят и обычные вызовы, и отправка файлов многочастной формой
    return fetch(new Request(TELEGRAM + url.pathname + url.search, request));
  }
};

// Спрашивает у Telegram, кто мы такие, и переводит ответ на человеческий.
// Заодно показывает, где именно рвётся цепочка: до Cloudflare, от Cloudflare
// до Telegram, или уже на самом токене
async function check(env) {
  if (!env.TOKEN) {
    return say('Секрет TOKEN не задан.\n'
      + 'Settings → Variables and Secrets → Add → тип Secret, имя TOKEN, значение — токен бота.', 500);
  }

  let answer;
  try {
    answer = await fetch(TELEGRAM + '/bot' + env.TOKEN + '/getMe');
  } catch (failure) {
    return say('Cloudflare не дозвонился до Telegram: ' + failure + '\n'
      + 'Такое бывает редко и обычно проходит само — попробуйте через минуту.', 502);
  }

  const said = await answer.text();

  if (answer.status === 404) {
    return say('Telegram не узнал токен (404).\n'
      + 'Связь есть, дело в самом токене: возьмите его заново у @BotFather\n'
      + '(/mybots → бот → API Token) и перепишите секрет TOKEN целиком.\n\n'
      + said, 404);
  }

  if (!answer.ok) {
    return say('Telegram ответил ' + answer.status + ':\n\n' + said, answer.status);
  }

  let name = '';
  try {
    name = JSON.parse(said).result.username;
  } catch (unreadable) {
    return say('Telegram ответил непонятно:\n\n' + said, 502);
  }

  return say('Всё работает. Бот: @' + name + '\n'
    + 'Этот адрес можно вписывать в bridge.telegram.api\n\n'
    + said, 200);
}

function say(text, status) {
  return new Response(text, {
    status,
    headers: { 'content-type': 'text/plain; charset=utf-8' }
  });
}
