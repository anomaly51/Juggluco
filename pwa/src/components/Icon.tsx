import type { SVGProps } from 'react'

export type IconName =
  | 'activity'
  | 'arrow-down'
  | 'arrow-up'
  | 'check'
  | 'clock'
  | 'cloud-off'
  | 'download'
  | 'droplet'
  | 'food'
  | 'gear'
  | 'history'
  | 'insulin'
  | 'refresh'
  | 'shield'
  | 'smartphone'
  | 'sun'
  | 'trash'
  | 'warning'
  | 'wifi'
  | 'x'

interface IconProps extends SVGProps<SVGSVGElement> {
  name: IconName
  size?: number
}

const paths: Record<IconName, React.ReactNode> = {
  activity: <path d="M3 12h4l2.4-6 4.2 12 2.4-6h5" />,
  'arrow-down': <><path d="M12 5v14" /><path d="m18 13-6 6-6-6" /></>,
  'arrow-up': <><path d="M12 19V5" /><path d="m6 11 6-6 6 6" /></>,
  check: <path d="m5 12 4 4L19 6" />,
  clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
  'cloud-off': <><path d="m3 3 18 18" /><path d="M6.6 6.7A6 6 0 0 0 8 18h8.7" /><path d="M18.7 15.7A5 5 0 0 0 13 8.1" /></>,
  download: <><path d="M12 3v12" /><path d="m7 10 5 5 5-5" /><path d="M5 21h14" /></>,
  droplet: <path d="M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11Z" />,
  food: <><path d="M6 3v8" /><path d="M3 3v5a3 3 0 0 0 6 0V3" /><path d="M6 11v10" /><path d="M16 3v18" /><path d="M16 3c3 2 4 5 4 8h-4" /></>,
  gear: <><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6v.2h-4V21a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H2.8v-4H3a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1A1.7 1.7 0 0 0 9 4.6 1.7 1.7 0 0 0 10 3V2.8h4V3a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.2v4H21a1.7 1.7 0 0 0-1.6 1Z" /></>,
  history: <><path d="M3 12a9 9 0 1 0 3-6.7L3 8" /><path d="M3 3v5h5" /><path d="M12 7v5l3 2" /></>,
  insulin: <><path d="m14 4 6 6" /><path d="m12 6 6 6" /><path d="M5 20 16 9" /><path d="m3 17 4 4" /><path d="m15 3 6 6" /></>,
  refresh: <><path d="M20 7v5h-5" /><path d="M19 12a7 7 0 1 0-2 5" /></>,
  shield: <><path d="M12 3 5 6v5c0 4.6 2.8 8 7 10 4.2-2 7-5.4 7-10V6l-7-3Z" /><path d="m9 12 2 2 4-4" /></>,
  smartphone: <><rect x="6" y="2" width="12" height="20" rx="2" /><path d="M10 18h4" /></>,
  sun: <><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></>,
  trash: <><path d="M4 7h16" /><path d="M9 3h6l1 4H8l1-4Z" /><path d="m6 7 1 14h10l1-14" /><path d="M10 11v6M14 11v6" /></>,
  warning: <><path d="M10.3 4.2 2.7 18a2 2 0 0 0 1.8 3h15a2 2 0 0 0 1.8-3L13.7 4.2a2 2 0 0 0-3.4 0Z" /><path d="M12 9v4M12 17h.01" /></>,
  wifi: <><path d="M4 9a12 12 0 0 1 16 0" /><path d="M7 12.5a7.5 7.5 0 0 1 10 0" /><path d="M10 16a3 3 0 0 1 4 0" /><path d="M12 20h.01" /></>,
  x: <><path d="m6 6 12 12" /><path d="m18 6-12 12" /></>,
}

export function Icon({ name, size = 22, ...props }: IconProps) {
  return (
    <svg
      aria-hidden="true"
      focusable="false"
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      {...props}
    >
      {paths[name]}
    </svg>
  )
}
