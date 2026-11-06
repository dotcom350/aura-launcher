# Novedades de Aura

Formato: cada versión tiene su sección `## [X.Y] - fecha`. Lo que todavía no se ha publicado va en
`## [Sin publicar]`. `scripts/release.ps1` convierte esa sección en la de la versión nueva, y GitHub
usa estas notas en la Release y en la landing. Escribe cada cambio como una línea que empiece por `- `.

## [Sin publicar]

## [1.8] - 2026-11-05

- El proyecto se prepara para GitHub: README completo, versión en `version.properties` y publicación automática con GitHub Actions (APK firmado en Releases y landing en GitHub Pages).
- Se quitan los 5 fondos de macOS (son de Apple y no se pueden publicar). Quedan los 18 de naturaleza de Wikimedia Commons; las configuraciones guardadas se migran solas.
- Las versiones publicadas se firman con una clave fija, así se actualizan encima sin perder los ajustes.
- Después de actualizarse, Aura vuelve a arrancar sola el panel web y el botón Inicio (antes había que abrirla a mano).

## [1.7] - 2026-11-05

- Panel web reorganizado: en el PC los ajustes del Fire TV van en una columna a la derecha y las tarjetas se reparten en dos columnas equilibradas.
- En el móvil ya se pueden arrastrar las apps para ordenarlas, y cada app tiene botones ▲▼.
- Instalar apps desde la web (subiendo un APK o con un enlace), desinstalarlas y buscar en la Appstore de Amazon.
- Emparejar un dispositivo nuevo con un código que se aprueba en la TV.
- Código de seguridad (PIN) para el panel web y el control por voz.
- Aura se abre sola al encender el Fire TV.
- La versión se ve en los Ajustes de la TV y en el panel web.

## Antes de la 1.7

Las primeras versiones no llevaban número (todas decían 1.0). Lo que se fue añadiendo:

- Bienvenida de primera instalación que desactiva rastreo, anuncios por intereses, reproducción automática, sonidos y el salvapantallas de Amazon, con animación.
- Salvapantallas propio (hora, fecha y clima sobre los fondos) y control del apagado de pantalla.
- Fondos de vídeo: YouTube, enlace directo o archivo subido.
- Botón Inicio capturado: siempre lleva a Aura; mantenido abre los Ajustes de Aura.
- Atajos a los ajustes del Fire TV (Wi-Fi, pantalla, apps…) desde la TV y la web.
- Fondos de naturaleza por franja horaria o según la app enfocada, clima, idioma español/inglés, reloj de 12/24 h, tipos y tamaños de letra.
- Panel web instalable (PWA) con mando, apps, fondos y ajustes; conexión por QR.
- Servidor MCP para controlar la TV desde un asistente.
