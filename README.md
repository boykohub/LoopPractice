# Music Practice

An Android music practice player.

- Plays MP3, WAV, FLAC, M4A/AAC, OGG and Opus files, plus the audio track of video files.
- Shows a scrolling waveform: drag to scrub, tap to jump, pinch to zoom. An overview strip shows the whole song.
- Changes speed from 25% to 200% and shifts pitch by ±12 semitones, independently. Two stretch engines:
  - **Melody:** [Signalsmith Stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch) (MIT)
  - **Drums:** [Rubber Band](https://breakfastquay.com/rubberband/) with crisp transients (GPL)
- Multiple loops (A, B, C, …): tap a letter to jump there and loop it.
- Loop chaining: "A plays 2×, then B", and so on.
- Jump ±1 s and ±5 s; while a loop is playing, the jumps stay inside it.
- Speed trainer; background playback with lock-screen controls (next/previous switch loops).
- Remembers loops, speed, pitch, engine and position for every song.

## Install

On the phone, open
**https://github.com/boykohub/LoopPractice/releases/latest/download/MusicPractice.apk**,
then open the downloaded file.

Every push to `main` is built by GitHub Actions and published as a new release.

Because the app includes Rubber Band, this project is under the GPL.
