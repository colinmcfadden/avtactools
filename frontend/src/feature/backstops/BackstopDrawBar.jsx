import Icon from "../ui/Icon";
import "./backstops.css";

/** Over the map while a backstop is drawn, so a phone with its sheet down can still finish one. */
const BackstopDrawBar = ({ points, onUndo, onCancel, onFinish }) => (
  <div className="backstop-draw-bar" role="status">
    <div className="backstop-draw-bar__text">
      <b>Drawing a backstop.</b> Click the map beyond the LZ/PZ to lay out the line.
      {points > 0 && ` ${points} ${points === 1 ? "point" : "points"} so far.`}
    </div>
    <div className="backstop-draw-bar__actions">
      <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onUndo} disabled={points === 0}>
        Undo point
      </button>
      <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel}>
        Cancel
      </button>
      <button type="button" className="ui-btn ui-btn--primary ui-btn--38" onClick={onFinish} disabled={points < 2}>
        <Icon name="check" size={15} />
        <span>{points < 2 ? "Add two points" : "Finish"}</span>
      </button>
    </div>
  </div>
);

export default BackstopDrawBar;
