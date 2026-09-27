# Echo (mobile)

Paul's Echo assistant as an Android app. It's the same personality and commands as the desktop `echo.py`, with Google Gemini as the brain in place of Ollama.

**Download:** https://github.com/renaldibosco/echo/releases/latest/download/Echo.apk

GitHub builds the APK on every push (see `.github/workflows/build-apk.yml`).

## What it can do
- Tap the orb and talk, or type. Turn on **HEY ECHO** for hands-free while the app is open
- "open youtube / whatsapp / camera / any installed app", "open wifi settings"
- "play <song>" (YouTube) · "play <song> on spotify" · "youtube <topic>"
- "search for <anything>" · "research <topic>" (Gemini with live Google Search)
- "torch on/off" · "set an alarm for 6 30 am" · "set a timer for 5 minutes"
- "call 98xxxxxxxx" · "what time is it" · "what's the date"
- "goodbye" puts it on standby. Anything else goes to Gemini

## Setup
First launch asks for a free Gemini API key from https://aistudio.google.com/apikey. The key is stored only on the phone.
