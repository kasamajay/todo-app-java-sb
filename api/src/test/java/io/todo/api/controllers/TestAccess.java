package io.todo.api.controllers;

import io.todo.api.AppState;
import io.todo.api.model.User;

/** Exposes package-private controller helpers to tests in other packages. */
public final class TestAccess {

    private TestAccess() {
    }

    public static User issueTwoFactorChallenge(AppState state, User user) {
        return AuthController.issueTwoFactorChallenge(state, user);
    }
}
