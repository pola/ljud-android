//! ljud's built-in player: librespot running as a Spotify Connect device,
//! playing through Android's AAudio. Exposed to Kotlin via JNI
//! (class `app.ljud.NativePlayer`).

use std::ffi::c_void;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread::JoinHandle;
use std::time::Duration;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use librespot_connect::{ConnectConfig, Spirc};
use librespot_core::authentication::Credentials;
use librespot_core::cache::Cache;
use librespot_core::config::DeviceType;
use librespot_core::{Session, SessionConfig};
use librespot_playback::audio_backend::{Sink, SinkError, SinkResult};
use librespot_playback::config::{Bitrate, PlayerConfig};
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;
use librespot_playback::mixer::{softmixer::SoftMixer, Mixer, MixerConfig};
use librespot_playback::player::{Player, PlayerEvent};
use log::{error, info, warn};
use ndk_sys as ffi;
use sha1::{Digest, Sha1};
use tokio::sync::watch;

/// librespot's desktop ("keymaster") client id; OAuth tokens for the player
/// must be issued for this client (see `PlayerAuth` on the Kotlin side).
const KEYMASTER_CLIENT_ID: &str = "65b708073fc0480ea92a077233ca87bd";

static STATUS: Mutex<String> = Mutex::new(String::new());
static RUNNING: Mutex<Option<Running>> = Mutex::new(None);
static LOG_INIT: OnceLock<()> = OnceLock::new();
static PLAYING: AtomicBool = AtomicBool::new(false);
static SPIRC: Mutex<Option<Arc<Spirc>>> = Mutex::new(None);

struct Running {
    stop: watch::Sender<bool>,
    thread: JoinHandle<()>,
}

fn set_status(s: impl Into<String>) {
    let s = s.into();
    info!("status: {s}");
    *STATUS.lock().unwrap() = s;
}

fn init_log() {
    LOG_INIT.get_or_init(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("ljudplayer"),
        );
    });
}

/// Stable device id derived from the name (like librespot does), so the
/// phone keeps the same Spotify Connect id across restarts.
fn device_id(name: &str) -> String {
    hex::encode(Sha1::digest(name.as_bytes()))
}

fn cache_for(dir: &str) -> Option<Cache> {
    let creds = PathBuf::from(dir).join("credentials");
    let volume = PathBuf::from(dir).join("volume");
    match Cache::new(Some(creds), Some(volume), None, None) {
        Ok(c) => Some(c),
        Err(e) => {
            warn!("cannot create cache: {e}");
            None
        }
    }
}

// ------------------------------------------------------------------ audio

/// Minimal owned AAudio output stream (16-bit stereo PCM, 44.1 kHz).
///
/// Uses the raw NDK API: the `ndk` crate's `AudioStream::write` treats the
/// (positive) number of written frames as an error.
struct Stream(*mut ffi::AAudioStream);

impl Stream {
    fn open() -> SinkResult<Stream> {
        let fail = |what: &str, code: i32| SinkError::ConnectionRefused(format!("AAudio {what} failed: {code}"));
        unsafe {
            let mut builder: *mut ffi::AAudioStreamBuilder = std::ptr::null_mut();
            let r = ffi::AAudio_createStreamBuilder(&mut builder);
            if r != ffi::AAUDIO_OK as i32 {
                return Err(fail("createStreamBuilder", r));
            }
            ffi::AAudioStreamBuilder_setDirection(builder, ffi::AAUDIO_DIRECTION_OUTPUT as _);
            ffi::AAudioStreamBuilder_setFormat(builder, ffi::AAUDIO_FORMAT_PCM_I16 as _);
            ffi::AAudioStreamBuilder_setChannelCount(builder, librespot_playback::NUM_CHANNELS as i32);
            ffi::AAudioStreamBuilder_setSampleRate(builder, librespot_playback::SAMPLE_RATE as i32);
            ffi::AAudioStreamBuilder_setUsage(builder, ffi::AAUDIO_USAGE_MEDIA as _);
            ffi::AAudioStreamBuilder_setContentType(builder, ffi::AAUDIO_CONTENT_TYPE_MUSIC as _);
            ffi::AAudioStreamBuilder_setPerformanceMode(builder, ffi::AAUDIO_PERFORMANCE_MODE_POWER_SAVING as _);
            let mut stream: *mut ffi::AAudioStream = std::ptr::null_mut();
            let r = ffi::AAudioStreamBuilder_openStream(builder, &mut stream);
            ffi::AAudioStreamBuilder_delete(builder);
            if r != ffi::AAUDIO_OK as i32 || stream.is_null() {
                return Err(fail("openStream", r));
            }
            let s = Stream(stream);
            let r = ffi::AAudioStream_requestStart(s.0);
            if r != ffi::AAUDIO_OK as i32 {
                return Err(fail("requestStart", r));
            }
            Ok(s)
        }
    }

    fn disconnected(&self) -> bool {
        unsafe { ffi::AAudioStream_getState(self.0) == ffi::AAUDIO_STREAM_STATE_DISCONNECTED as i32 }
    }

    /// Blocking write; returns frames written or a negative AAudio error code.
    fn write(&self, samples: &[i16], frames: i32) -> Result<usize, i32> {
        let r = unsafe { ffi::AAudioStream_write(self.0, samples.as_ptr() as *const c_void, frames, 2_000_000_000) };
        if r < 0 { Err(r) } else { Ok(r as usize) }
    }
}

impl Drop for Stream {
    fn drop(&mut self) {
        unsafe {
            ffi::AAudioStream_requestStop(self.0);
            ffi::AAudioStream_close(self.0);
        }
    }
}

/// A librespot sink that writes to an AAudio stream.
struct AAudioSink {
    stream: Option<Stream>,
}

impl AAudioSink {
    fn ensure_stream(&mut self) -> SinkResult<&Stream> {
        if self.stream.as_ref().is_none_or(|s| s.disconnected()) {
            self.stream = None;
            self.stream = Some(Stream::open()?);
        }
        Ok(self.stream.as_ref().unwrap())
    }

    fn write_all(&mut self, samples: &[i16]) -> SinkResult<()> {
        let channels = librespot_playback::NUM_CHANNELS as usize;
        let mut offset = 0;
        let mut retried = false;
        while offset < samples.len() {
            let stream = self.ensure_stream()?;
            let frames = ((samples.len() - offset) / channels) as i32;
            match stream.write(&samples[offset..], frames) {
                Ok(written) => offset += written * channels,
                Err(code) if !retried => {
                    // E.g. the output device changed (headphones): reopen once.
                    warn!("audio write failed ({code}); reopening stream");
                    self.stream = None;
                    retried = true;
                }
                Err(code) => return Err(SinkError::OnWrite(format!("AAudio error {code}"))),
            }
        }
        Ok(())
    }
}

impl Sink for AAudioSink {
    fn start(&mut self) -> SinkResult<()> {
        self.ensure_stream()?;
        Ok(())
    }

    fn stop(&mut self) -> SinkResult<()> {
        // Close the stream so we don't hold the audio device while paused.
        self.stream = None;
        Ok(())
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        match packet {
            AudioPacket::Samples(samples) => {
                let pcm = converter.f64_to_s16(&samples);
                self.write_all(&pcm)
            }
            AudioPacket::Raw(_) => Err(SinkError::InvalidParams("raw packets not supported".into())),
        }
    }
}

// ------------------------------------------------------------------ connect loop

async fn run(cache_dir: String, name: String, token: Option<String>, mut stop: watch::Receiver<bool>) {
    let session_config = SessionConfig {
        client_id: KEYMASTER_CLIENT_ID.to_string(),
        device_id: device_id(&name),
        tmp_dir: PathBuf::from(&cache_dir).join("tmp"),
        ..SessionConfig::default()
    };
    let _ = std::fs::create_dir_all(&session_config.tmp_dir);
    let cache = cache_for(&cache_dir);

    let mixer: std::sync::Arc<dyn Mixer> = match SoftMixer::open(MixerConfig::default()) {
        Ok(m) => std::sync::Arc::new(m),
        Err(e) => {
            set_status(format!("error: mixer: {e}"));
            return;
        }
    };
    let player_config = PlayerConfig {
        bitrate: Bitrate::Bitrate320,
        ..PlayerConfig::default()
    };
    let connect_config = ConnectConfig {
        name: name.clone(),
        device_type: DeviceType::Smartphone,
        initial_volume: (u16::MAX as u32 * 70 / 100) as u16,
        ..ConnectConfig::default()
    };

    let mut session = Session::new(session_config.clone(), cache.clone());
    let player = Player::new(player_config, session.clone(), mixer.get_soft_volume(), || {
        Box::new(AAudioSink { stream: None }) as Box<dyn Sink>
    });

    // Track whether audio is playing (the app keeps the phone awake only then).
    let mut events = player.get_player_event_channel();
    tokio::spawn(async move {
        while let Some(ev) = events.recv().await {
            match ev {
                PlayerEvent::Playing { .. } => PLAYING.store(true, Ordering::Relaxed),
                PlayerEvent::Paused { .. } | PlayerEvent::Stopped { .. } => {
                    PLAYING.store(false, Ordering::Relaxed)
                }
                _ => {}
            }
        }
    });

    let mut token = token;
    let mut backoff = Duration::from_secs(2);
    loop {
        if *stop.borrow() {
            break;
        }
        // Prefer the reusable credentials librespot cached after the first
        // login; the OAuth token is only needed once.
        let credentials = match cache.as_ref().and_then(Cache::credentials) {
            Some(c) => c,
            None => match token.take() {
                Some(t) => Credentials::with_access_token(t),
                None => {
                    set_status("error: not logged in");
                    return;
                }
            },
        };

        if session.is_invalid() {
            session = Session::new(session_config.clone(), cache.clone());
            player.set_session(session.clone());
        }

        set_status("connecting");
        let spirc = Spirc::new(
            connect_config.clone(),
            session.clone(),
            credentials,
            player.clone(),
            mixer.clone(),
        )
        .await;

        let (spirc, task) = match spirc {
            Ok(s) => s,
            Err(e) => {
                error!("connect failed: {e}");
                let msg = e.to_string();
                if !session.is_invalid() {
                    session.shutdown();
                }
                if msg.contains("BadCredentials") || msg.contains("bad credentials") {
                    set_status("error: login rejected; log in again");
                    return;
                }
                set_status(format!("offline: {msg} (retrying)"));
                tokio::select! {
                    _ = tokio::time::sleep(backoff) => {}
                    _ = stop.changed() => break,
                }
                backoff = (backoff * 2).min(Duration::from_secs(60));
                continue;
            }
        };

        let spirc = Arc::new(spirc);
        *SPIRC.lock().unwrap() = Some(spirc.clone());
        set_status("ready");
        backoff = Duration::from_secs(2);
        tokio::pin!(task);
        tokio::select! {
            _ = &mut task => {
                warn!("connection ended; reconnecting");
                SPIRC.lock().unwrap().take();
                PLAYING.store(false, Ordering::Relaxed);
                if !session.is_invalid() {
                    session.shutdown();
                }
                set_status("offline: reconnecting");
                tokio::select! {
                    _ = tokio::time::sleep(Duration::from_secs(2)) => {}
                    _ = stop.changed() => break,
                }
            }
            _ = stop.changed() => {
                SPIRC.lock().unwrap().take();
                let _ = spirc.shutdown();
                let _ = tokio::time::timeout(Duration::from_secs(3), &mut task).await;
                break;
            }
        }
    }
    if !session.is_invalid() {
        session.shutdown();
    }
    PLAYING.store(false, Ordering::Relaxed);
    set_status("stopped");
}

// ------------------------------------------------------------------ JNI

fn jstr(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(|s| s.into())
}

/// Start the player. `token` may be null if credentials are cached.
#[no_mangle]
pub extern "system" fn Java_app_ljud_NativePlayer_start(
    mut env: JNIEnv,
    _class: JClass,
    cache_dir: JString,
    name: JString,
    token: JString,
) -> jboolean {
    init_log();
    let (Some(cache_dir), Some(name)) = (jstr(&mut env, &cache_dir), jstr(&mut env, &name)) else {
        return JNI_FALSE;
    };
    let token = jstr(&mut env, &token).filter(|t| !t.is_empty());

    let mut running = RUNNING.lock().unwrap();
    if running.as_ref().is_some_and(|r| !r.thread.is_finished()) {
        return JNI_TRUE;
    }
    let (tx, rx) = watch::channel(false);
    set_status("starting");
    let thread = std::thread::Builder::new()
        .name("ljudplayer".into())
        .spawn(move || {
            let rt = match tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()
            {
                Ok(rt) => rt,
                Err(e) => {
                    set_status(format!("error: runtime: {e}"));
                    return;
                }
            };
            rt.block_on(run(cache_dir, name, token, rx));
            rt.shutdown_timeout(Duration::from_secs(2));
        });
    match thread {
        Ok(thread) => {
            *running = Some(Running { stop: tx, thread });
            JNI_TRUE
        }
        Err(e) => {
            set_status(format!("error: thread: {e}"));
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_app_ljud_NativePlayer_stop(_env: JNIEnv, _class: JClass) {
    let r = RUNNING.lock().unwrap().take();
    if let Some(r) = r {
        let _ = r.stop.send(true);
        let _ = r.thread.join();
    }
}

#[no_mangle]
pub extern "system" fn Java_app_ljud_NativePlayer_status(env: JNIEnv, _class: JClass) -> jstring {
    let s = STATUS.lock().unwrap().clone();
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_app_ljud_NativePlayer_hasCredentials(
    mut env: JNIEnv,
    _class: JClass,
    cache_dir: JString,
) -> jboolean {
    let Some(dir) = jstr(&mut env, &cache_dir) else { return JNI_FALSE };
    if cache_for(&dir).and_then(|c| c.credentials()).is_some() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_app_ljud_NativePlayer_deviceId(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jstring {
    let id = jstr(&mut env, &name).map(|n| device_id(&n)).unwrap_or_default();
    env.new_string(id).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_app_ljud_NativePlayer_isPlaying(_env: JNIEnv, _class: JClass) -> jboolean {
    if PLAYING.load(Ordering::Relaxed) { JNI_TRUE } else { JNI_FALSE }
}

/// Pause playback (e.g. on audio focus loss). Spotify sees it as a normal pause.
#[no_mangle]
pub extern "system" fn Java_app_ljud_NativePlayer_pause(_env: JNIEnv, _class: JClass) {
    if let Some(s) = SPIRC.lock().unwrap().as_ref() {
        let _ = s.pause();
    }
}
