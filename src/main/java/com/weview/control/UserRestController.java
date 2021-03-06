package com.weview.control;

import com.weview.control.exceptions.*;
import com.weview.model.UserDataForClient;
import com.weview.model.UserFriendData;
import com.weview.model.loggedinUserHandling.RedisLoggedinUserRepository;
import com.weview.model.persistence.UserLoginData;
import com.weview.model.persistence.entities.FriendRequestNotification;
import com.weview.model.persistence.entities.User;
import com.weview.model.persistence.UserRepository;
import com.weview.utils.ExceptionInspector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Date;

/**
 * This class is the Http RESTFul control, it is in charge of all user
 * related Http endpoints
 */
@RestController
public class UserRestController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RedisLoggedinUserRepository loggedInUserRepository;

    @RequestMapping(value = "/login", method = RequestMethod.POST)
    public String login(@RequestBody UserLoginData userLoggingIn) {

        String username = userLoggingIn.getUsername();
        String password = userLoggingIn.getPassword();
        User user = userRepository.findByUsername(username);

        if (user == null) {
            throw new UserNotFoundException();
        }

        if (!user.getPassword().equals(password)) {
            throw new InvalidPasswordException();
        }

        loggedInUserRepository.login(username);

        return "/user/" + username;
    }

    @RequestMapping(value = "/logout", method = RequestMethod.POST)
    public String logout(HttpServletResponse response, @RequestParam String username) throws IOException {

        if (!loggedInUserRepository.isLoggedin(username)) {
            throw new UserNotLoggedInException();
        }

        loggedInUserRepository.logout(username);

        //TODO: Should we destroy the user's player? What if others continue to watch? Unsubscribe with ref count?

        return "/";
    }

    @RequestMapping(value = "/signup", method = RequestMethod.POST)
    public String signup(@RequestBody User newUser) {
        try {
            userRepository.save(newUser);
            userRepository.flush();
            loggedInUserRepository.login(newUser.getUsername());
        }
        catch (DataAccessException e) {
            Throwable cause = ExceptionInspector.getRootCause(e);
            if (cause instanceof SQLIntegrityConstraintViolationException) {
                SQLIntegrityConstraintViolationException sqlConstraint =
                        (SQLIntegrityConstraintViolationException)cause;
                throw new UserFieldConstraintException(newUser, sqlConstraint);
            }
            else {
                throw e;
            }
        }
        catch (Exception e) {
            throw e;
        }

        return "/user/" + newUser.getUsername();
    }

    @RequestMapping(value = "/user/{username}/user-data", method = RequestMethod.GET)
    public UserDataForClient getUserClientData(@PathVariable("username") String username) {

        //TODO: Handle friends
        User theUser = getLoggedInUser(username);
        return getUserDataForClient(theUser);
    }

    @RequestMapping(value = "/user/{username}/friends", method = RequestMethod.GET)
    public UserFriendData getUserFriends(@PathVariable("username") String username) {

        UserFriendData userFriends = new UserFriendData();
        User user = getLoggedInUser(username);

        for (User friend :user.getAllFriends()) {
            UserDataForClient friendData = getUserDataForClient(friend);
            userFriends.getFriends().add(friendData);
        }

        return userFriends;
    }

    private User getLoggedInUser(String username) throws UserNotFoundException, UserNotLoggedInException{
        User user = userRepository.findByUsername(username);
        if (user == null) {
            throw new UserNotFoundException();
        }
        if (!loggedInUserRepository.isLoggedin(user.getUsername())) {
            throw new UserNotLoggedInException();
        }

        return user;
    }

    @RequestMapping(value = "/user/{username}/make-friend/{requesting}", method = RequestMethod.POST)
    public void makeFriend(@PathVariable("username") String usernameToBefriend,
                           @PathVariable("requesting") String usernameRequesting) {

        User userRequesting = userRepository.findByUsername(usernameRequesting);
        User userToBefriend = userRepository.findByUsername(usernameToBefriend);

        if (userToBefriend == null || userRequesting == null) {
            throw new UserNotFoundException();
        }

        userRequesting.addFriend(userToBefriend);
        userToBefriend.removeFriendRequest(usernameRequesting);

        userRepository.save(userRequesting);
        userRepository.save(userToBefriend);
        userRepository.flush();
    }

    @RequestMapping(value = "/user/{username}/decline-friend/{requesting}", method = RequestMethod.POST)
    public void declineFriend(@PathVariable("username") String usernameToBefriend,
                           @PathVariable("requesting") String usernameRequesting) {

        User userRequesting = userRepository.findByUsername(usernameRequesting);
        User userToBefriend = userRepository.findByUsername(usernameToBefriend);

        if (userToBefriend == null || userRequesting == null) {
            throw new UserNotFoundException();
        }

        userToBefriend.removeFriendRequest(usernameRequesting);
        userRepository.save(userToBefriend);
        userRepository.flush();
    }

    @RequestMapping(value = "/user/{username}/friend-request/{friend}", method = RequestMethod.POST)
    public void makeFriendRequest(@PathVariable("username") String usernameRequesting,
                                    @PathVariable("friend") String friendUsername) {

        User friend = null;
        if ((friend = userRepository.findByUsername(friendUsername)) == null) {
            throw new UserNotFoundException();
        }

        notifyUserOfFriendRequest(friend, usernameRequesting);

        //Returns JSON that indicates that the user is logged in
        //so that the requesting client can send him the request via websocket
//        Boolean isFriendLoggedIn = loggedInUserRepository.isLoggedin(friend.getUsername());
//        return "{\"isFriendLoggedIn\" : \"" + isFriendLoggedIn.toString() + "\"}";
    }

    @RequestMapping(value = "/user/{username}/search-friend", method = RequestMethod.GET)
    public UserDataForClient searchFriend(@PathVariable("username") String usernameRequesting,
                                    @RequestParam String searchParam) {
        User friend = null;
        if ((friend = userRepository.findByUsername(searchParam)) == null &&
                (friend = userRepository.findByEmail(searchParam)) == null) {
            throw new UserNotFoundException();
        }
        return new UserDataForClient(friend.getUsername(), friend.getEmail(), friend.getFirstName(), friend.getLastName(), null, null, friend.getIcon());
    }

    private void notifyUserOfFriendRequest(User user, String usernameRequesting) {
        String message = MessageFormat.format("{0} has invited you to be his friend", usernameRequesting);
        Date date = new Date();
        user.addFriendRequest(new FriendRequestNotification(message, date, usernameRequesting));
        userRepository.save(user);
        userRepository.flush();
    }

    private UserDataForClient getUserDataForClient(User user) {
        String username = user.getUsername();
        Boolean isLoggedin = loggedInUserRepository.isLoggedin(username);
        Boolean isDbxToken = (user.getDbxToken() != null);
        return new UserDataForClient(username, user.getEmail(), user.getFirstName(), user.getLastName(), isLoggedin, isDbxToken, user.getIcon());
    }

    @RequestMapping(value = "/user/{username}/friend-requests-notifications", method = RequestMethod.GET)
    public Collection<FriendRequestNotification> getFriendRequests(@PathVariable("username") String username) {
        User user = getLoggedInUser(username);
        return user.getFriendRequests().values();
    }

//    @RequestMapping(value = "/user/{username}/photo", method = RequestMethod.GET)
//    public byte[] getUserPhoto(@PathVariable("username") String username) {
//        User user = getLoggedInUser(username);
//        return user.getPhoto();
//    }
//
//    @RequestMapping(value = "/user/{username}/photo", method = RequestMethod.POST)
//    public void uploadUserPhoto(@PathVariable("username") String username, byte[] photo) {
//        User user = getLoggedInUser(username);
//        user.setPhoto(photo);
//        userRepository.save(user);
//        userRepository.flush();
//    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(UserFieldConstraintException.class)
    public UserFieldViolationErrorInfo handleUserFieldException(UserFieldConstraintException ex) {
        return new UserFieldViolationErrorInfo(ex.getException(), ex.getViolatingUser());
    }

}
