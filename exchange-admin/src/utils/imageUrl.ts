import { imageLocation } from '../../../exchange-frontend/src/utils/imageLocation'
export function getImageUrl(url: string | null | undefined, accountMode?: string): string { return imageLocation(url, location.origin, accountMode) }
