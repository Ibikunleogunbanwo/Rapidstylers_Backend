package com.macrotel.rapidstylers.media.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each caller uploads into a folder only they own, named so it can't be guessed. */
class UploadOwnershipTest {

    private final UploadOwnership ownership = new UploadOwnership();

    @Test
    void aSignUpAndAnAccountGetDifferentFolders() {
        String signup = ownership.folderFor("SIGNUP_UPLOAD", "Chloe@Example.com");
        String account = ownership.folderFor("STYLER", "CM1234");

        assertTrue(signup.matches("signup_[0-9a-f]{16}"));
        assertTrue(account.matches("acct_[0-9a-f]{16}"));
        // The same email in another case is the same sign-up.
        assertEquals(signup, ownership.folderFor("SIGNUP_UPLOAD", "chloe@example.com"));
        assertNotEquals(signup, ownership.folderFor("SIGNUP_UPLOAD", "someone@example.com"));
    }

    @Test
    void onlyTheOwnerOwnsAFile() {
        String mine = "rapid_stylers/profile/" + ownership.folderFor("SIGNUP_UPLOAD", "chloe@example.com") + "/face";

        assertTrue(ownership.owns("SIGNUP_UPLOAD", "chloe@example.com", mine));
        assertFalse(ownership.owns("SIGNUP_UPLOAD", "someone@example.com", mine));
        assertFalse(ownership.owns("STYLER", "chloe@example.com", mine));
        assertFalse(ownership.owns("SIGNUP_UPLOAD", "chloe@example.com", "rapid_stylers/profile/face"));
    }

    @Test
    void theKeyChangesTheFolderName() {
        UploadOwnership other = new UploadOwnership();
        other.secret = "another-secret";

        assertNotEquals(ownership.folderFor("STYLER", "CM1234"), other.folderFor("STYLER", "CM1234"));
    }

    @Test
    void signUpUploadsAreRecognisedByTheirFolder() {
        String signup = "rapid_stylers/id/" + ownership.folderFor("SIGNUP_UPLOAD", "a@b.c") + "/card";
        String account = "rapid_stylers/portfolio/" + ownership.folderFor("STYLER", "CM1234") + "/work";

        assertTrue(UploadOwnership.isSignupUpload(signup));
        assertFalse(UploadOwnership.isSignupUpload(account));
        assertFalse(UploadOwnership.isSignupUpload("rapid_stylers/profile/signup_old"));
        assertTrue(UploadOwnership.hasOwnerFolder(account));
        assertFalse(UploadOwnership.hasOwnerFolder("rapid_stylers/profile/face"));
    }
}
