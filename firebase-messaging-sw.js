importScripts('https://www.gstatic.com/firebasejs/13.4.0/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/13.4.0/firebase-messaging-compat.js');

firebase.initializeApp({
  apiKey: 'AIzaSyCvFYHan3DwTfFty91t71gYcQIfXBpNXMk',
  authDomain: 'barcode-scanner-515214.firebaseapp.com',
  projectId: 'barcode-scanner-515214',
  storageBucket: 'barcode-scanner-515214.firebasestorage.app',
  messagingSenderId: '653886610803',
  appId: '1:653886610803:web:53595e92937750bf43b906',
  measurementId: 'G-CCSYNLP8QT'
});

const messaging = firebase.messaging();
messaging.onBackgroundMessage((payload) => {
  const title = payload.notification?.title || payload.data?.title || 'VELTRIX';
  const body = payload.notification?.body || payload.data?.body || '';
  self.registration.showNotification(title, {
    body,
    icon: './favicon.ico',
    badge: './favicon.ico',
    data: { url: 'https://elsadaii.github.io/barcode-scanner-excel/' }
  });
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const target = event.notification.data?.url || 'https://elsadaii.github.io/barcode-scanner-excel/';
  event.waitUntil(clients.matchAll({type:'window',includeUncontrolled:true}).then((windows) => {
    for (const client of windows) {
      if ('focus' in client) { client.navigate(target); return client.focus(); }
    }
    return clients.openWindow(target);
  }));
});
