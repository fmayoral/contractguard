interface BadgeProps {
  tone: 'ok' | 'bad' | 'warn' | 'busy';
  children: React.ReactNode;
}

export function Badge({ tone, children }: BadgeProps) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}
