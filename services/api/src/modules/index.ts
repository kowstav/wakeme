import type { RouteDef } from '../http/route.ts';
import { alarmRoutes } from './alarms/routes.ts';
import { billingRoutes } from './billing/routes.ts';
import { challengeRoutes } from './challenges/routes.ts';
import { commandRoutes } from './commands/routes.ts';
import { conversationRoutes } from './conversations/routes.ts';
import { deviceRoutes } from './devices/routes.ts';
import { grantRoutes } from './grants/routes.ts';
import { identityRoutes } from './identity/routes.ts';
import { mediaRoutes } from './media/routes.ts';
import { moderationRoutes } from './moderation/routes.ts';
import { operationsRoutes } from './operations/routes.ts';
import { relationshipRoutes } from './relationships/routes.ts';
import { safetyRoutes } from './safety/routes.ts';
import { syncRoutes } from './sync/routes.ts';

export function allRoutes(): RouteDef[] {
  return [
    ...operationsRoutes,
    ...identityRoutes,
    ...deviceRoutes,
    ...relationshipRoutes,
    ...grantRoutes,
    ...commandRoutes,
    ...alarmRoutes,
    ...challengeRoutes,
    ...mediaRoutes,
    ...conversationRoutes,
    ...safetyRoutes,
    ...moderationRoutes,
    ...billingRoutes,
    ...syncRoutes,
  ];
}
