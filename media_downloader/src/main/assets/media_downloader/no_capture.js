/*
 * No camera and no microphone, for every page this browser shows.
 *
 * Asking Chromium for the list of capture devices asks the device's camera service for each
 * camera's stream configuration, and a phone whose camera firmware answers that badly makes the
 * framework throw - `AssertionError: At least one stream configuration for IMPLEMENTATION_DEFINED
 * must exist`, from inside `android.hardware.camera2`. It reaches Chromium's JNI boundary, which
 * rethrows it on its own thread, and the app the WebView lives in dies. Nothing on the Java side
 * can catch that, so the ask itself is what has to stop.
 *
 * Nothing here needs a camera: this is a browser for finding media on a page, and a capture
 * permission the module is asked for is refused anyway. So the pages are told there is nothing to
 * capture with - which is also the truth of it, for them.
 */
(function () {
    'use strict';
    try {
        var refuse = function () {
            return Promise.reject(new DOMException('Not allowed in this browser', 'NotAllowedError'));
        };
        var devices = {
            enumerateDevices: function () { return Promise.resolve([]); },
            getSupportedConstraints: function () { return {}; },
            getUserMedia: refuse,
            getDisplayMedia: refuse,
            selectAudioOutput: refuse,
            addEventListener: function () {},
            removeEventListener: function () {},
            dispatchEvent: function () { return false; },
            ondevicechange: null
        };
        /* A getter, because the real one is a getter on the prototype and a page may read it from
           either the instance or the prototype chain. */
        Object.defineProperty(Navigator.prototype, 'mediaDevices', {
            get: function () { return devices; },
            configurable: true
        });
        /* The shapes that came before mediaDevices, which some pages still feature-detect. */
        ['getUserMedia', 'webkitGetUserMedia', 'mozGetUserMedia'].forEach(function (name) {
            try {
                Object.defineProperty(Navigator.prototype, name, {
                    value: function (constraints, onOk, onFail) {
                        if (typeof onFail === 'function') {
                            onFail(new DOMException('Not allowed in this browser', 'NotAllowedError'));
                        }
                    },
                    configurable: true
                });
            } catch (ignored) { /* A page that froze it keeps whatever it froze. */ }
        });
    } catch (ignored) {
        /* A page that has sealed its own navigator keeps it; nothing here is worth an error. */
    }
})();
