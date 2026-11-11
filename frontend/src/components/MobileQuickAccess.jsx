import React from 'react';
import { UNIT_TYPES } from '../feature/unit/UnitIcons';
import { symbolDataUri } from '../feature/symbols/milsym';
import Icon from '../feature/ui/Icon';
import Menu, { MenuItem, useMenu } from '../feature/ui/Menu';
import BackstopGlyph from '../feature/backstops/BackstopGlyph';
import { RAIL, cardTools, railCapacity, railFit, railRows, railTools, undoShown } from '../feature/phoneFrame/phoneFrame';
import './MobileQuickAccess.css';

/*
 * The phone's tools on the map (850 px and below): a rail at the map's right edge, standing on the button that
 * hides and shows it, where a thumb reaches. It offers the tools in the order the app is used (phoneFrame.js):
 * the route tool and Set Target; with a target, Analyze and Draw LZ, in words while they are the next step; once
 * the LZ/PZ is analysed, the planning tools, two to a row. What a short map has no room for goes behind More.
 *
 * Beside the hide button, undo and redo, only while there is something to take back. At the left, above Layers,
 * the LZ card's Capture, and Export once a capture area is set.
 *
 * All of it is placed in the map (`.shell-map`), from its edges, never in a stacking context of its own: the map
 * ends where the bottom sheet begins, so nothing here is ever under the sheet (AGENTS.md §14).
 */

const GOLD = '#f4c542';

const routeGlyph = (on) => (
  <svg viewBox="0 0 24 24" fill="none" stroke={on ? '#ffffff' : '#64D2FF'} strokeWidth="2" width="24" height="24" aria-hidden="true">
    <circle cx="4" cy="20" r="2" />
    <circle cx="12" cy="9" r="2" />
    <circle cx="20" cy="15" r="2" />
    <path d="M5.5 18.5 L10.5 10.5 M13.7 10 L18.3 14" />
  </svg>
);

const pzGlyph = (
  <svg width="24" height="24" viewBox="0 0 100 80" style={{ overflow: 'visible' }} aria-hidden="true">
    <path d="M 40,25 L 10,25 L 10,15 L -10,40 L 10,65 L 10,55 L 40,55 Z" fill="#00b5e2" stroke="none" />
    <circle cx="70" cy="40" r="15" fill="none" stroke="#ef4444" strokeWidth="8" />
  </svg>
);

const sectorGlyph = (
  <svg viewBox="0 0 50 50" width="24" height="24" aria-hidden="true">
    <polygon points="25,5 45,40 5,40" fill="rgba(147, 112, 219, 0.5)" stroke="#9370DB" strokeWidth="3" />
  </svg>
);

const goAroundGlyph = (side) => (
  <svg viewBox="0 0 100 100" width="24" height="24" style={{ overflow: 'visible' }} aria-hidden="true">
    <path
      d={side === 'left' ? 'M90,50 Q60,50 40,80 L50,85 L20,95 L5,65 L15,70 Q30,20 90,20 Z' : 'M10,50 Q40,50 60,80 L50,85 L80,95 L95,65 L85,70 Q70,20 10,20 Z'}
      fill="#FFC107"
      stroke="black"
      strokeWidth="4"
    />
  </svg>
);

const builderGlyph = (
  <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="#00b5e2" strokeWidth="2" aria-hidden="true">
    <rect x="4" y="6" width="16" height="12" rx="2" />
    <path d="M12 9v6M9 12h6" />
  </svg>
);

const cropIcon = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ width: '16px', height: '16px' }} aria-hidden="true">
    <path d="M6.13 1L6 16a2 2 0 0 0 2 2h15"></path><path d="M1 6.13L16 6a2 2 0 0 1 2 2v15"></path>
  </svg>
);

const downloadIcon = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ width: '16px', height: '16px' }} aria-hidden="true">
    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line>
  </svg>
);

const glyphOf = (tool) => {
  switch (tool.key) {
    case 'route': return routeGlyph(tool.pressed);
    case 'target': return <Icon name="star" size={20} color={tool.enabled ? GOLD : 'currentColor'} />;
    case 'analyze': return <Icon name="scan" size={20} />;
    case 'draw': return <Icon name="hexagon" size={20} />;
    case 'helo': return <img src="/icons/helicopter.png" alt="" className="qa-icon-img" />;
    case 'pz': return pzGlyph;
    case 'sector': return sectorGlyph;
    case 'backstop': return <BackstopGlyph />;
    case 'unit': return <img src="/icons/tactical/infantry.svg" alt="" className="qa-icon-img" />;
    case 'goAround': return goAroundGlyph('right');
    default: return null;
  }
};

// The choices behind a tool that offers more than one thing (a unit's symbol, a go-around's side).
const choicesOf = (tool, act) => {
  if (tool.key === 'unit') {
    return [
      { key: 'unit-builder', title: 'Build a MIL-STD symbol…', icon: builderGlyph, run: act.onOpenUnitBuilder },
      ...UNIT_TYPES.map((unit) => ({
        key: `unit-${unit.id}`,
        title: unit.label,
        icon: <img src={symbolDataUri(unit.sidc, { size: 24 })} alt="" style={{ width: 24, height: 24 }} />,
        run: () => act.addUnit?.(unit),
      })),
    ];
  }
  if (tool.key === 'goAround') {
    return [
      { key: 'ga-left', title: 'Go-around left', icon: goAroundGlyph('left'), run: () => act.addGoAround?.('left') },
      { key: 'ga-right', title: 'Go-around right', icon: goAroundGlyph('right'), run: () => act.addGoAround?.('right') },
    ];
  }
  return null;
};

const runOf = (tool, act) => ({
  route: act.toggleRouteSketch,
  target: act.onSetTarget,
  analyze: act.onAnalyze,
  draw: act.onToggleDrawing,
  helo: act.addHelo,
  pz: act.addPZMarker,
  sector: act.addSector,
  backstop: act.onToggleBackstop,
}[tool.key]);

const RailButton = ({ tool, act }) => {
  const menu = useMenu();
  const choices = choicesOf(tool, act);
  const className = [
    'qa-btn',
    tool.wide ? 'qa-pill' : '',
    tool.tone === 'primary' && tool.enabled ? 'qa-pill--primary' : '',
    tool.tone === 'drawing' ? 'qa-pill--drawing' : '',
    (tool.pressed && !tool.wide) || menu.open ? 'active-qa-btn' : '',
  ].filter(Boolean).join(' ');

  if (choices) {
    return (
      <>
        <button type="button" className={className} disabled={!tool.enabled} title={tool.hint} aria-label={tool.label} {...menu.buttonProps}>
          {glyphOf(tool)}
        </button>
        <Menu open={menu.open} onClose={menu.close} anchorRef={menu.anchorRef} label={tool.label} width={240} align="end">
          {choices.map((choice) => (
            <MenuItem key={choice.key} icon={choice.icon} title={choice.title} onClose={menu.close} onSelect={choice.run} />
          ))}
        </Menu>
      </>
    );
  }

  return (
    <button
      type="button"
      className={className}
      disabled={!tool.enabled}
      title={tool.hint}
      // A step in words is named by them; an icon by its label.
      aria-label={tool.wide ? undefined : tool.label}
      aria-pressed={tool.pressed === undefined ? undefined : tool.pressed}
      onClick={() => tool.enabled && runOf(tool, act)?.()}
    >
      {glyphOf(tool)}
      {tool.wide && <span>{tool.label}</span>}
    </button>
  );
};

// What a short map had no room for, as a list: every tool by name, a tool with choices as each of them.
const MoreTools = ({ tools, act }) => {
  const menu = useMenu();
  return (
    <>
      <button type="button" className={`qa-btn${menu.open ? ' active-qa-btn' : ''}`} title="More tools" aria-label="More tools" {...menu.buttonProps}>
        <Icon name="more" size={22} />
      </button>
      <Menu open={menu.open} onClose={menu.close} anchorRef={menu.anchorRef} label="More tools" width={250} align="end">
        {tools.flatMap((tool) => {
          const choices = choicesOf(tool, act);
          if (choices) {
            return choices.map((choice) => (
              <MenuItem key={choice.key} icon={choice.icon} title={choice.title} disabled={!tool.enabled} onClose={menu.close} onSelect={choice.run} />
            ));
          }
          return [
            <MenuItem
              key={tool.key}
              icon={<span className="qa-more-glyph">{glyphOf(tool)}</span>}
              title={tool.pressed ? `${tool.label} (on)` : tool.label}
              disabled={!tool.enabled}
              onClose={menu.close}
              onSelect={runOf(tool, act)}
            />,
          ];
        })}
      </Menu>
    </>
  );
};

const MobileQuickAccess = ({
  features = {},
  // Where the active LZ/PZ stands (phoneFrame.js's railStage).
  hasTarget = false,
  analyzed = false,
  // False in a finished Mission Pack, or for a viewer of one: nothing may be changed, so no tool is offered.
  editable = true,
  isDrawingLZ = false,
  isSketching = false,
  isDrawingBackstop = false,
  isDrawingFlot = false,
  // The map's height, which says how many rows fit; not measured, every row is drawn.
  mapHeight,
  // The tools folded away by the button under them, and the whole frame put away by a tap on the map.
  folded = false,
  onToggleFolded,
  hidden = false,
  undo,
  onSetTarget,
  toggleRouteSketch,
  onAnalyze,
  onToggleDrawing,
  addHelo,
  addPZMarker,
  addSector,
  onToggleBackstop,
  addUnit,
  onOpenUnitBuilder,
  addGoAround,
  // An analysed LZ/PZ can be exported by someone who may not change it (a finished Mission Pack).
  canExport = false,
  enableExportMode,
  onDownloadClick,
  exportBox,
  isExporting,
  exportProgress,
}) => {
  const act = { onSetTarget, toggleRouteSketch, onAnalyze, onToggleDrawing, addHelo, addPZMarker, addSector, onToggleBackstop, addUnit, onOpenUnitBuilder, addGoAround };
  const tools = railTools({
    features,
    hasTarget,
    analyzed,
    editable,
    drawingBoundary: isDrawingLZ,
    sketchingRoute: isSketching,
    drawingBackstop: isDrawingBackstop,
    drawingFlot: isDrawingFlot,
  });
  const { rows, more } = railFit(railRows(tools), railCapacity(mapHeight));
  const card = cardTools({ features, canExport, hasCaptureArea: Boolean(exportBox) });
  const showUndo = undoShown(undo);
  const measures = { '--rail-size': `${RAIL.size}px`, '--rail-gap': `${RAIL.gap}px` };

  return (
    <>
      {tools.length > 0 && (
        <div id="phone-rail" className="phone-rail" role="group" aria-label="Map tools" style={measures} hidden={folded} inert={hidden}>
          {more.length > 0 && (
            <div className="phone-rail__row">
              <MoreTools tools={more} act={act} />
            </div>
          )}
          {rows.map((row) => (
            <div className="phone-rail__row" key={row.map((tool) => tool.key).join('+')}>
              {row.map((tool) => <RailButton key={tool.key} tool={tool} act={act} />)}
            </div>
          ))}
        </div>
      )}

      {(tools.length > 0 || showUndo) && (
        <div className="phone-foot" style={measures} inert={hidden}>
          {showUndo && (
            <>
              <button type="button" className="qa-btn" onClick={undo.undo} disabled={!undo.canUndo} title={undo.undoTitle} aria-label={undo.undoTitle}>
                <Icon name="undo" size={22} />
              </button>
              <button type="button" className="qa-btn" onClick={undo.redo} disabled={!undo.canRedo} title={undo.redoTitle} aria-label={undo.redoTitle}>
                <Icon name="redo" size={22} />
              </button>
            </>
          )}
          {tools.length > 0 && (
            <button
              type="button"
              className={`qa-btn phone-foot__toggle${folded ? ' qa-pill' : ''}`}
              aria-expanded={!folded}
              aria-controls="phone-rail"
              aria-label={folded ? 'Show tools' : 'Hide tools'}
              title={folded ? 'Show the map tools' : 'Hide the map tools'}
              onClick={onToggleFolded}
            >
              {folded && <span>Tools</span>}
              <Icon name={folded ? 'chevronUp' : 'chevronDown'} size={22} />
            </button>
          )}
        </div>
      )}

      {/* The LZ card: Capture, then Export, above the Layers button. Placed in the map as Layers is, so they end
          where the sheet begins. */}
      {card.capture && (
        <div className="mobile-export-container" inert={hidden}>
          {isExporting && (
            <div className="mobile-progress-bg">
              <div className="mobile-progress-fill" style={{ width: `${exportProgress}%` }}></div>
            </div>
          )}
          <div className="mobile-export-buttons">
            <button type="button" onClick={enableExportMode} title="Set the capture area" className="mobile-pill-btn blue-pill">
              {cropIcon} <span>Capture</span>
            </button>
            {card.export && (
              <button
                type="button"
                onClick={() => !isExporting && onDownloadClick()}
                disabled={isExporting}
                title="Export LZ card"
                className={`mobile-pill-btn green-pill ${isExporting ? 'disabled' : ''}`}
              >
                {downloadIcon} <span>{isExporting ? 'Processing...' : 'Export'}</span>
              </button>
            )}
          </div>
        </div>
      )}
    </>
  );
};

export default MobileQuickAccess;
