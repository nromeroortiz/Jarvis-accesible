# Jarvis Accesible (Android 10 a 15)

Asistente de voz nativo para Android diseñado específicamente para **personas no videntes o con disminución visual severa**. Funciona **100% sin tocar la pantalla**, arranca solo al encender el teléfono y tiene integración profunda con **WhatsApp (mensajes y notas de voz)**, **Llamadas en altavoz**, **YouTube directo**, **Alarmas silenciosas** y **Búsquedas en Google explicadas por voz** usando **Google Gemini 2.5 Flash**.

---

## ✨ Funciones principales (100% Manos Libres)

1. **Activación sin tocar la pantalla ni mantener botones apretados:**
   - **Botón físico:** Tu tío presiona **dos veces seguidas el botón de Subir Volumen** (y lo suelta), siente una vibración/pitido y habla tranquilo. Cuando hace silencio, Jarvis procesa el pedido.
   - **Por voz ("Hola Jarvis" / "Asistente"):** Incluye un interruptor en la configuración (o por comando de voz *"Desactivá la escucha por voz"* / *"Activá la escucha por voz"*) para encenderlo o ahorrar batería cuando prefiera.
   - **Botón gigante de alto contraste:** Si abre la app, toda la pantalla es un único botón gigante amarillo/negro.

2. **WhatsApp Completo (Texto + Audios de Voz):**
   - **No interrumpe:** Escucha silenciosamente los WhatsApp que llegan en segundo plano. Cuando tu tío pregunta *"¿Tengo algún mensaje?"*, le dice quién escribió, a qué hora y qué dice (o si le mandaron un audio).
   - **Reproducir audios recibidos (`.opus`):** Al decir *"Reproducí el audio"*, busca el último archivo `.opus` en las carpetas de WhatsApp (compatible con Android 10 y con la ruta `Android/media/com.whatsapp` de Android 11 a 15) y lo reproduce directo.
   - **Enviar mensajes dictados por texto:** Le lee primero el mensaje y le pregunta *"¿Se lo mando? Decí sí o cancelar"*. Si es respuesta a alguien que escribió recién, lo envía por `RemoteInput` en segundo plano sin siquiera abrir WhatsApp; si es un contacto nuevo, lo busca en la agenda, abre el chat y el Servicio de Accesibilidad presiona **Enviar** solo.
   - **Grabar y enviar un audio con la voz real de tu tío:** Al decir *"Mandale un audio a Juan"*, da un pitido, graba la voz de tu tío con el micrófono, detecta automáticamente cuando termina de hablar (2.5 segundos de silencio), comparte el audio con el contacto en WhatsApp y el Servicio de Accesibilidad toca el botón **Enviar** automáticamente.

3. **Llamadas Telefónicas en Altavoz:**
   - Al decir *"Llamá a María"*, busca el contacto en la agenda, inicia la llamada y **activa el altavoz automáticamente** para que pueda hablar sin acercarse el teléfono ni tocar la pantalla.

4. **YouTube sin quedarse trabado en la búsqueda:**
   - Al decir *"Poné Los Chalchaleros en YouTube"*, busca el primer ID de video real y lanza la reproducción directa (`vnd.youtube:VIDEO_ID`).
   - Al decir *"Pará la música"* o *"Apagá YouTube"*, detiene inmediatamente cualquier reproducción multimedia.

5. **Alarmas sin pantallas de confirmación y Estado del Celular:**
   - *"Poneme una alarma a las 8 y media"* usa `EXTRA_SKIP_UI = true` para que quede activada sin tocar nada.
   - *"¿Qué hora es?"* / *"¿Cuánta batería tengo?"* informa la hora, el porcentaje de batería, si está enchufado cargando y si tiene Wi-Fi o datos móviles.

6. **Reconocedor de Billetes de Pesos Argentinos por Cámara (`"¿Qué billete es este?"`):**
   - Cuando tu tío dice *"¿Qué billete es este?"* (o *"¿De cuánto es este billete?"*, *"Mirá este billete"*), Jarvis le pide que sostenga el billete frente a la cámara trasera, **enciende automáticamente la linterna/flash y el autofoco**, saca la foto solo sin que tenga que tocar ningún botón en pantalla (dando un pitido para avisarle que ya puede bajar la mano) y le responde directo: ***"Es de 1000"***, ***"Es de 500"***, ***"Es de 10000"***, ***"Es de 20000"***, etc.
   - Funciona tanto con **Gemini 2.5 Flash Visión** (reconoce números, colores, próceres y animales como el Hornero, Yaguareté, San Martín, Belgrano, Alberdi, Carrillo/Grierson, e incluso suma si tiene varios billetes en la mano) como con **lectura óptica 100% offline (ML Kit)** por si está en la calle sin internet.

7. **Búsquedas en Google leídas al detalle (Gemini 2.5 Flash + Google Search Grounding):**
   - Cualquier consulta general (*"¿Cómo salió Boca ayer?"*, *"¿Cómo va a estar el clima hoy?"*, *"Buscame qué es..."*) la busca en vivo en Google y se la explica de forma cálida y conversacional, sin pedirle nunca que mire links en la pantalla.

---

## 🛠️ Cómo generar el archivo APK para instalarle a tu tío

Tenés **2 formas muy fáciles** de obtener el archivo `.apk`:

### Opción 1: Automático en la nube con GitHub Actions (Sin instalar Android Studio)
El proyecto ya incluye el flujo [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml):
1. Subí esta carpeta a un repositorio de tu cuenta de GitHub.
2. Entrá a la pestaña **Actions** de tu repositorio en GitHub.
3. Vas a ver la ejecución **Compilar APK de Jarvis Accesible** y, al terminar, abajo de todo aparece el archivo descargable **`JarvisAccesible-APK`** con el `app-debug.apk` listo para pasar por WhatsApp o cable USB al celular de tu tío.

### Opción 2: Desde tu PC con Android Studio
1. Abrí **Android Studio** -> **Open** -> seleccioná la carpeta `C:\Users\Nico\Desktop\Agente`.
2. Esperá a que sincronice Gradle y andá al menú **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**.
3. El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📱 Configuración inicial en el celular de tu tío (Solo la primera vez, te lleva 2 minutos)

1. Instalá el APK en el celular de tu tío y abrí **Jarvis Accesible**.
2. Tocá el botón de arriba a la derecha: **`Configuración (Sobrino)`**.
3. Pegá tu clave gratuita de **Google Gemini API** (la creás gratis en [Google AI Studio](https://aistudio.google.com/apikey)).
4. Tocá los **5 botones de permisos** (`A`, `B`, `C`, `D` y `E`) hasta que los 5 queden en **🟢 Verde**:
   - *Nota para Android 13, 14 y 15:* Al intentar activar el **Servicio de Accesibilidad** por primera vez en un APK instalado por fuera de Play Store, Android puede mostrar un cartel de *"Ajuste restringido"*. Para habilitarlo: andá a **Ajustes -> Aplicaciones -> Jarvis Accesible -> tocá los 3 puntitos arriba a la derecha -> "Permitir ajustes restringidos"** y luego volvé a activar Accesibilidad y Notificaciones.
5. Tocá **`Ver Botón Gigante`** y listo: aunque reinicies el celular, Jarvis arrancará solo.
