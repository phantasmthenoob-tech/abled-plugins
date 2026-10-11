package net.abled.loginguard;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * The AuthMe prejoin screen, rebuilt on Paper's dialog API and shown to LoginGuard's own players
 * instead of the upstream's.
 *
 * <p>Visually the same layout the upstream shows (title, one or two password inputs, a green
 * Login/Register button and a gray Cancel button), but the click handling is entirely
 * LoginGuard's: the submit buttons carry inline {@link DialogActionCallback}s that call straight
 * into {@link LoginManager}, so the same lockdown/bridge/record logic the commands use is driven
 * by the GUI. Cancel closes without side effects.
 *
 * <p>Everything downstream of a submit is unchanged: the register path still verifies against
 * the upstream through the same headless worker (the "fake player" flow), the record files under
 * {@code logins/<upstream-ip>/} are written by the same {@link LoginStore}, and the commands
 * still work.
 */
final class AuthDialogs {

    private AuthDialogs() {
    }

    /** Shows the login screen. One password input, Login submits, Cancel just closes. */
    static void showLogin(JavaPlugin plugin, LoginManager manager, Player player) {
        List<DialogInput> inputs = List.of(passwordInput("password", "Password", 64));
        List<DialogBody> body = List.of(plain("Please log in to play."));
        List<ActionButton> buttons = List.of(button("Login", NamedTextColor.GREEN,
                (response, audience) -> manager.login(player, response.getText("password"))));
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(dialogBase("Login", body, inputs))
                .type(DialogType.multiAction(buttons, cancelExit(), 1)));
        show(plugin, player, dialog);
    }

    /** Shows the register screen: password and confirm inputs, Register submits, Cancel closes. */
    static void showRegister(JavaPlugin plugin, LoginManager manager, Player player) {
        List<DialogInput> inputs = List.of(
                passwordInput("password", "Password", 64),
                passwordInput("confirm", "Confirm", 64));
        List<DialogBody> body = List.of(plain("Create an account to play."));
        List<ActionButton> buttons = List.of(button("Register", NamedTextColor.GREEN,
                (response, audience) -> manager.register(player,
                        response.getText("password"), response.getText("confirm"))));
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(dialogBase("Register", body, inputs))
                .type(DialogType.multiAction(buttons, cancelExit(), 1)));
        show(plugin, player, dialog);
    }

    /** Re-shows the correct screen after an error (same shape, so the player can retry in place). */
    static void resendLogin(JavaPlugin plugin, LoginManager manager, Audience audience) {
        if (audience instanceof Player player) {
            showLogin(plugin, manager, player);
        }
    }

    private static DialogBase dialogBase(String title, List<DialogBody> body, List<DialogInput> inputs) {
        return DialogBase.builder(Component.text(title).color(NamedTextColor.GOLD))
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .body(body)
                .inputs(inputs)
                .build();
    }

    private static DialogBody plain(String text) {
        return DialogBody.plainMessage(Component.text(text, NamedTextColor.YELLOW));
    }

    private static DialogInput passwordInput(String key, String label, int maxLength) {
        return DialogInput.text(key, Component.text(label))
                .maxLength(maxLength)
                .labelVisible(true)
                .build();
    }

    /** A submit button whose click calls back into the given handler on the main thread. */
    private static ActionButton button(String label, NamedTextColor color,
                                       io.papermc.paper.registry.data.dialog.action.DialogActionCallback handler) {
        return ActionButton.builder(Component.text(label).color(color))
                .width(150)
                .action(DialogAction.customClick(handler, null))
                .build();
    }

    /** The gray Cancel button: closes the dialog, nothing else. */
    private static ActionButton cancelExit() {
        return ActionButton.builder(Component.text("Cancel").color(NamedTextColor.GRAY))
                .width(150)
                .action(DialogAction.staticAction(
                        net.kyori.adventure.text.event.ClickEvent.callback(__ -> { /* close only */ })))
                .build();
    }

    private static void show(JavaPlugin plugin, Player player, Dialog dialog) {
        // Must run on the main thread; scheduler already guarantees this for join/repeat paths.
        player.showDialog(dialog);
    }
}
