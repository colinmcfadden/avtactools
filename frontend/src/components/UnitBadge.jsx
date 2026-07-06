import React, { useRef, useState } from "react";

/**
 * Synthesized red-tailed-hawk style screech (the classic movie "falcon" cry):
 * a sawtooth sweep with fast vibrato through a bandpass, descending over
 * ~1.5s. Used when /sounds/falcon.mp3 isn't present in public/.
 */
const synthFalconScreech = () => {
  const AudioCtx = window.AudioContext || window.webkitAudioContext;
  if (!AudioCtx) return;
  const ctx = new AudioCtx();
  const t = ctx.currentTime;

  const osc = ctx.createOscillator();
  osc.type = "sawtooth";
  osc.frequency.setValueAtTime(2000, t);
  osc.frequency.exponentialRampToValueAtTime(1500, t + 0.2);
  osc.frequency.exponentialRampToValueAtTime(550, t + 1.35);

  const vibrato = ctx.createOscillator();
  vibrato.frequency.value = 28;
  const vibratoGain = ctx.createGain();
  vibratoGain.gain.value = 70;
  vibrato.connect(vibratoGain);
  vibratoGain.connect(osc.frequency);

  const filter = ctx.createBiquadFilter();
  filter.type = "bandpass";
  filter.frequency.setValueAtTime(1800, t);
  filter.frequency.exponentialRampToValueAtTime(700, t + 1.4);
  filter.Q.value = 1.2;

  const gain = ctx.createGain();
  gain.gain.setValueAtTime(0.0001, t);
  gain.gain.exponentialRampToValueAtTime(0.35, t + 0.08);
  gain.gain.setValueAtTime(0.35, t + 0.9);
  gain.gain.exponentialRampToValueAtTime(0.0001, t + 1.5);

  osc.connect(filter);
  filter.connect(gain);
  gain.connect(ctx.destination);
  osc.start(t);
  vibrato.start(t);
  osc.stop(t + 1.6);
  vibrato.stop(t + 1.6);
  setTimeout(() => ctx.close(), 2000);
};

const playFalconSound = () => {
  let handled = false;
  const fallback = () => {
    if (handled) return;
    handled = true;
    synthFalconScreech();
  };
  const audio = new Audio("/sounds/falcon.mp3");
  audio.addEventListener("error", fallback);
  audio.play().then(() => {
    handled = true;
  }).catch(fallback);
};

const UnitBadge = () => {
  const [spinning, setSpinning] = useState(false);
  const [flying, setFlying] = useState(false);
  const flyTimeout = useRef(null);

  const handleClick = () => {
    setSpinning(true);
    if (!flying) {
      setFlying(true);
      playFalconSound();
      clearTimeout(flyTimeout.current);
      flyTimeout.current = setTimeout(() => setFlying(false), 2700);
    }
  };

  return (
    <>
      <button
        className={`unit-badge ${spinning ? "spinning" : ""}`}
        onClick={handleClick}
        onAnimationEnd={() => setSpinning(false)}
        title="A Co. 1-171st GSAB Falcons"
      >
        <img src="/img/171-patch-tsp.png" alt="A Co. 1-171st GSAB Falcons" />
      </button>

      {flying && (
        <div className="falcon-flyby" aria-hidden="true">
          🦅
        </div>
      )}
    </>
  );
};

export default UnitBadge;
