-- The notification that the stage of a bunch rose (CHARACTER_GROWN) is no longer made, so the ones that were already
-- made are removed. Only this type is removed: the other notifications are not touched.
--
-- A push job that points to a removed notification keeps its row and loses the link (push_jobs.notification_id is
-- ON DELETE SET NULL, V15). The type itself stays in the allowed types of the table (V12, V15), because the code that
-- reads the type still knows it, and it goes with the stage of the bunch.
DELETE FROM notifications
WHERE type = 'CHARACTER_GROWN';
