-- =====================================================================
-- V8: new passwords for the V4 demo users. Nobody held the V4 passwords,
-- so these replace them. Only BCrypt (cost 12) hashes live here; the
-- passwords themselves are shared in the team chat, never in the repo.
-- To make a hash for a new user or a reset: ./scripts/hash-password.sh
-- =====================================================================

UPDATE users SET password_hash = '$2a$12$iY1dycCM6vyaZ018hwyXputcOeUMxCiVksznKlVviIhBjrnv/Ybr.' WHERE username = 'wanjiku';
UPDATE users SET password_hash = '$2a$12$HAAQjyrotYYvl8AX.QSSfeiANMYghDQDaHWppXnnLDfiezSp6BxaK' WHERE username = 'otieno';
UPDATE users SET password_hash = '$2a$12$JQXz81/jS4mNXBTe/mBQH.fmJeG/x6IFitKGzftQv3gjN.qsDs/ka' WHERE username = 'ops.maker';
UPDATE users SET password_hash = '$2a$12$k2JypT/rME5lHvEiJJDkhuInFvtiXqNXE82VJWT7P5XQoamOqlK72' WHERE username = 'ops.checker';
UPDATE users SET password_hash = '$2a$12$.Xo/HfDEojo3OH3w75fkBOXZRMX5nVXv/JjQQaBWpkJoj.DhheCZi' WHERE username = 'ops.checker2';
