interface StatTileProps {
  label: string;
  value: string;
  /** Optional context line under the value, e.g. "3 active now". */
  detail?: string;
}

/** A headline number. Deliberately proportional figures — tabular digits look loose at display size. */
export function StatTile({ label, value, detail }: StatTileProps) {
  return (
    <div className="stat-tile">
      <span className="stat-label">{label}</span>
      <span className="stat-value">{value}</span>
      {detail && <span className="stat-detail">{detail}</span>}
    </div>
  );
}
